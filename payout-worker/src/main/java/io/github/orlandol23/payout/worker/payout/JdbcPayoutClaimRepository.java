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
                   updated_at = :now
             WHERE id = :id
               AND (
                        (status = 'PENDING'
                         AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                     OR (status = 'PROCESSING' AND locked_at < :staleBefore)
                   )
            RETURNING id, amount, currency, correlation_id, attempts, created_at
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
                   updated_at = :now
              FROM due
             WHERE payouts.id = due.id
               AND (
                        (payouts.status = 'PENDING'
                         AND (payouts.next_attempt_at IS NULL OR payouts.next_attempt_at <= :now))
                     OR (payouts.status = 'PROCESSING' AND payouts.locked_at < :staleBefore)
                   )
            RETURNING payouts.id, payouts.amount, payouts.currency,
                      payouts.correlation_id, payouts.attempts, payouts.created_at
            """;

    private static final String CONFIRM = """
            UPDATE payouts
               SET status         = 'CONFIRMED',
                   locked_at      = NULL,
                   next_attempt_at = NULL,
                   updated_at     = :now
             WHERE id = :id
               AND status = 'PROCESSING'
            """;

    /**
     * Back to the queue, due at {@code nextAttemptAt}, with the reason recorded.
     *
     * <p>Guarded by {@code status = 'PROCESSING'} like the other transitions, so
     * a worker whose lock was already reclaimed cannot reach across and reset a
     * payout another worker is holding.
     */
    private static final String SCHEDULE_RETRY = """
            UPDATE payouts
               SET status          = 'PENDING',
                   locked_at       = NULL,
                   next_attempt_at = :nextAttemptAt,
                   last_error      = :lastError,
                   updated_at      = :now
             WHERE id = :id
               AND status = 'PROCESSING'
            """;

    private static final String FAIL = """
            UPDATE payouts
               SET status          = 'FAILED',
                   locked_at       = NULL,
                   next_attempt_at = NULL,
                   last_error      = :lastError,
                   updated_at      = :now
             WHERE id = :id
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
    public void confirm(UUID payoutId, Instant now) {
        jdbcClient.sql(CONFIRM)
                .param("id", payoutId)
                .param("now", at(now))
                .update();
    }

    @Override
    public void scheduleRetry(UUID payoutId, Instant nextAttemptAt, String lastError, Instant now) {
        jdbcClient.sql(SCHEDULE_RETRY)
                .param("id", payoutId)
                .param("nextAttemptAt", at(nextAttemptAt))
                .param("lastError", PayoutClaimRepository.truncateLastError(lastError))
                .param("now", at(now))
                .update();
    }

    @Override
    public void fail(UUID payoutId, String lastError, Instant now) {
        jdbcClient.sql(FAIL)
                .param("id", payoutId)
                .param("lastError", PayoutClaimRepository.truncateLastError(lastError))
                .param("now", at(now))
                .update();
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
