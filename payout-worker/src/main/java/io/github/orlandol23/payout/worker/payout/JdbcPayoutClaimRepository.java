package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.worker.config.WorkerProperties;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The claim statements, written out in SQL.
 *
 * <p>{@link JdbcClient} rather than JPA, and the SQL is here in full rather than
 * behind a query method name. What makes the claim correct is the exact
 * {@code WHERE} clause: an ORM's optimistic locking would throw on a lost race
 * where this needs a silent zero-row update, and a {@code find} followed by a
 * {@code save} would put a read-then-write window in the middle of the one
 * operation that must not have one.
 */
@Repository
public class JdbcPayoutClaimRepository implements PayoutClaimRepository {

    /**
     * Take the row, count the attempt, stamp the lock, in one statement.
     *
     * <p>The two arms of the predicate are the two ways a payout can be
     * claimable. Either it is {@code PENDING} and due, meaning nobody holds it
     * and any backoff has expired, or it is {@code PROCESSING} under a lock
     * older than the stale-lock timeout, meaning the worker that held it is not
     * coming back. A row that is terminal, freshly locked, or not yet due
     * matches neither arm and the statement updates nothing.
     *
     * <p>{@code attempts = attempts + 1} increments at claim time rather than
     * after a settlement result, so a worker that dies mid-settlement has
     * already spent the attempt. That is the conservative direction: the
     * alternative is a crashed settlement that may or may not have reached a
     * provider being retried for free, forever.
     *
     * <p>{@code RETURNING} makes the claim and the read one round trip. Without
     * it there is a second query, and a window between the two in which the row
     * this worker now owns could be read as something else entirely.
     */
    private static final String CLAIM = """
            UPDATE payouts
               SET status     = 'PROCESSING',
                   attempts   = attempts + 1,
                   locked_at  = :now,
                   lock_token = :lockToken,
                   updated_at = :now
             WHERE id = :id
               AND (
                        (status = 'PENDING'
                         AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                     OR (status = 'PROCESSING' AND locked_at < :staleBefore)
                   )
            RETURNING id, lock_token, amount, currency, correlation_id, attempts, created_at
            """;

    /**
     * The same claim, without an id: take the oldest due rows, up to a limit.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes this safe to run on every
     * worker at once. Each scan locks the rows it selected and skips any row
     * another scan is already holding, so two workers sweeping simultaneously
     * divide the backlog instead of fighting over the front of it. Without
     * {@code SKIP LOCKED} the second worker would block on the first one's rows
     * and the scans would serialise; without {@code FOR UPDATE} both would
     * select the same ids and one would find them already claimed.
     *
     * <p>The CTE and the UPDATE are one statement, so they are one transaction
     * and one snapshot: nothing can slip between selecting a row and claiming
     * it. The predicate is repeated on the UPDATE anyway. It is redundant while
     * the lock is held, and it is what keeps the statement correct rather than
     * subtly wrong if the locking clause is ever edited out.
     */
    private static final String CLAIM_DUE = """
            WITH due AS (
                SELECT id
                  FROM payouts
                 WHERE (status = 'PENDING'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (status = 'PROCESSING' AND locked_at < :staleBefore)
                 ORDER BY created_at
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
            )
            UPDATE payouts
               SET status     = 'PROCESSING',
                   attempts   = attempts + 1,
                   locked_at  = :now,
                   lock_token = gen_random_uuid(),
                   updated_at = :now
              FROM due
             WHERE payouts.id = due.id
               AND (
                        (payouts.status = 'PENDING'
                         AND (payouts.next_attempt_at IS NULL OR payouts.next_attempt_at <= :now))
                     OR (payouts.status = 'PROCESSING' AND payouts.locked_at < :staleBefore)
                   )
            RETURNING payouts.id, payouts.lock_token, payouts.amount, payouts.currency,
                      payouts.correlation_id, payouts.attempts, payouts.created_at
            """;

    /**
     * Renews the lock without touching anything else.
     *
     * <p>Fenced on the token, so a worker whose lock was already reclaimed
     * renews nothing and is told so before it calls the provider. Deliberately
     * does not move {@code updated_at}: a heartbeat is not a change to the
     * payout, and letting it look like one would hide the last real transition
     * from anyone reading the table.
     */
    private static final String RENEW_LOCK = """
            UPDATE payouts
               SET locked_at = :now
             WHERE id = :id
               AND lock_token = :lockToken
               AND status = 'PROCESSING'
            """;

    private static final String CONFIRM = """
            UPDATE payouts
               SET status         = 'CONFIRMED',
                   locked_at      = NULL,
                   lock_token     = NULL,
                   next_attempt_at = NULL,
                   updated_at     = :now
             WHERE id = :id
               AND lock_token = :lockToken
               AND status = 'PROCESSING'
            """;

    /**
     * Back to the queue, due at {@code nextAttemptAt}, with the reason recorded.
     *
     * <p>Guarded by the lock token, not just by {@code status = 'PROCESSING'}.
     * The status alone does not identify a holder: a row whose stale lock
     * another worker has just reclaimed is still {@code PROCESSING}, so the
     * status guard passes for the worker that no longer owns it and this
     * statement would push a payout the new holder is actively settling back to
     * PENDING underneath it.
     */
    private static final String SCHEDULE_RETRY = """
            UPDATE payouts
               SET status          = 'PENDING',
                   locked_at       = NULL,
                   lock_token      = NULL,
                   next_attempt_at = :nextAttemptAt,
                   last_error      = :lastError,
                   updated_at      = :now
             WHERE id = :id
               AND lock_token = :lockToken
               AND status = 'PROCESSING'
            """;

    private static final String FAIL = """
            UPDATE payouts
               SET status          = 'FAILED',
                   locked_at       = NULL,
                   lock_token      = NULL,
                   next_attempt_at = NULL,
                   last_error      = :lastError,
                   updated_at      = :now
             WHERE id = :id
               AND lock_token = :lockToken
               AND status = 'PROCESSING'
            """;

    /**
     * {@code created_at} is read as {@code OffsetDateTime} and converted, rather
     * than as a {@code Timestamp}. A {@code timestamptz} read through
     * {@code Timestamp} is reinterpreted in the JVM's default zone, which is the
     * classic way a UTC column comes back an hour out on one machine and not on
     * another.
     */
    private static final RowMapper<ClaimedPayout> CLAIMED = (rs, rowNumber) -> new ClaimedPayout(
            rs.getObject("id", UUID.class),
            rs.getObject("lock_token", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("correlation_id"),
            rs.getInt("attempts"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbcClient;
    private final WorkerProperties properties;

    public JdbcPayoutClaimRepository(JdbcClient jdbcClient, WorkerProperties properties) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    @Override
    public Optional<ClaimedPayout> claim(UUID payoutId, Instant now) {
        return jdbcClient.sql(CLAIM)
                .param("id", payoutId)
                .param("lockToken", UUID.randomUUID())
                .param("now", at(now))
                .param("staleBefore", at(now.minus(properties.staleLock())))
                .query(CLAIMED)
                .optional();
    }

    /**
     * {@code @Transactional} because the lock the CTE takes has to be held by
     * something. It is one statement, so it would get an implicit transaction
     * anyway; declaring it says that the lock scope is deliberate rather than a
     * property of autocommit that a later edit could remove by accident.
     */
    @Override
    @Transactional
    public List<ClaimedPayout> claimDue(int limit, Instant now) {
        return jdbcClient.sql(CLAIM_DUE)
                .param("limit", limit)
                .param("now", at(now))
                .param("staleBefore", at(now.minus(properties.staleLock())))
                .query(CLAIMED)
                .list();
    }

    @Override
    public boolean renewLock(UUID payoutId, UUID lockToken, Instant now) {
        return jdbcClient.sql(RENEW_LOCK)
                .param("id", payoutId)
                .param("lockToken", lockToken)
                .param("now", at(now))
                .update() == 1;
    }

    @Override
    public boolean confirm(UUID payoutId, UUID lockToken, Instant now) {
        return jdbcClient.sql(CONFIRM)
                .param("id", payoutId)
                .param("lockToken", lockToken)
                .param("now", at(now))
                .update() == 1;
    }

    @Override
    public boolean scheduleRetry(UUID payoutId, UUID lockToken, Instant nextAttemptAt, String lastError, Instant now) {
        return jdbcClient.sql(SCHEDULE_RETRY)
                .param("id", payoutId)
                .param("lockToken", lockToken)
                .param("nextAttemptAt", at(nextAttemptAt))
                .param("lastError", PayoutClaimRepository.truncateLastError(lastError))
                .param("now", at(now))
                .update() == 1;
    }

    @Override
    public boolean fail(UUID payoutId, UUID lockToken, String lastError, Instant now) {
        return jdbcClient.sql(FAIL)
                .param("id", payoutId)
                .param("lockToken", lockToken)
                .param("lastError", PayoutClaimRepository.truncateLastError(lastError))
                .param("now", at(now))
                .update() == 1;
    }

    /**
     * Binds an instant as {@code timestamptz}.
     *
     * <p>{@link OffsetDateTime} at UTC, because that is the type the PostgreSQL
     * driver maps to {@code timestamptz} without consulting a session or JVM
     * time zone. Handing it an {@code Instant} or a {@code Timestamp} works
     * until something, somewhere, is not running in UTC.
     */
    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
