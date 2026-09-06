package io.github.orlandol23.payout.worker.payout;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The payouts table as a map, for the tests that need the pipeline but not a
 * database.
 *
 * <p>It exists so the Kafka half can be proven on a machine with no Docker: an
 * embedded broker plus this gives a real listener, a real settlement call and a
 * real dead letter, with only the storage swapped. What the SQL actually does
 * under concurrency is a database question and is answered by
 * {@code PayoutWorkerIT} against a real PostgreSQL, not here. A map that agrees
 * with the production statements is evidence about the pipeline, never about the
 * claim.
 *
 * <p>Every method is {@code synchronized}, which is the cheap in-memory stand-in
 * for the row lock PostgreSQL takes: two claims of one row are serialised and
 * the second one sees the state the first one left.
 */
public class InMemoryPayoutClaimRepository implements PayoutClaimRepository {

    /** The columns the worker touches, mutable because the statements update them. */
    public static final class Row {

        public final BigDecimal amount;
        public final String currency;
        public final String correlationId;
        public final Instant createdAt;

        public String status = "PENDING";
        public int attempts;
        public Instant lockedAt;
        public UUID lockToken;
        public Instant nextAttemptAt;
        public String lastError;

        Row(BigDecimal amount, String currency, String correlationId, Instant createdAt) {
            this.amount = amount;
            this.currency = currency;
            this.correlationId = correlationId;
            this.createdAt = createdAt;
        }
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private final Duration staleLock;

    public InMemoryPayoutClaimRepository(Duration staleLock) {
        this.staleLock = staleLock;
    }

    /** Stands in for {@code POST /payouts} having inserted a PENDING row. */
    public synchronized void insertPending(UUID id,
                                           BigDecimal amount,
                                           String currency,
                                           String correlationId,
                                           Instant createdAt) {
        rows.put(id, new Row(amount, currency, correlationId, createdAt));
    }

    public synchronized Row row(UUID id) {
        return rows.get(id);
    }

    @Override
    public synchronized Optional<ClaimedPayout> claim(UUID payoutId, Instant now) {
        Row row = rows.get(payoutId);
        if (row == null || !isClaimable(row, now)) {
            return Optional.empty();
        }
        row.status = "PROCESSING";
        row.attempts++;
        row.lockedAt = now;
        row.lockToken = UUID.randomUUID();
        return Optional.of(new ClaimedPayout(
                payoutId, row.lockToken, row.amount, row.currency, row.correlationId,
                row.attempts, row.createdAt));
    }

    /**
     * The same predicate, oldest first, up to the limit.
     *
     * <p>No stand-in for {@code SKIP LOCKED}: a map has no other transaction to
     * skip. That the real statement skips rows another worker is holding is
     * proven in {@code PayoutWorkerIT}, where there is a real lock to hold.
     */
    @Override
    public synchronized List<ClaimedPayout> claimDue(int limit, Instant now) {
        return rows.entrySet().stream()
                .filter(entry -> isClaimable(entry.getValue(), now))
                .sorted(Comparator.comparing(entry -> entry.getValue().createdAt))
                .limit(limit)
                .map(entry -> claim(entry.getKey(), now))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public synchronized boolean renewLock(UUID payoutId, UUID lockToken, Instant now) {
        Row row = held(payoutId, lockToken);
        if (row == null) {
            return false;
        }
        row.lockedAt = now;
        return true;
    }

    @Override
    public synchronized boolean confirm(UUID payoutId, UUID lockToken, Instant now) {
        Row row = held(payoutId, lockToken);
        if (row == null) {
            return false;
        }
        row.status = "CONFIRMED";
        row.lockedAt = null;
        row.lockToken = null;
        row.nextAttemptAt = null;
        return true;
    }

    @Override
    public synchronized boolean scheduleRetry(UUID payoutId,
                                              UUID lockToken,
                                              Instant nextAttemptAt,
                                              String lastError,
                                              Instant now) {
        Row row = held(payoutId, lockToken);
        if (row == null) {
            return false;
        }
        row.status = "PENDING";
        row.lockedAt = null;
        row.lockToken = null;
        row.nextAttemptAt = nextAttemptAt;
        row.lastError = PayoutClaimRepository.truncateLastError(lastError);
        return true;
    }

    @Override
    public synchronized boolean fail(UUID payoutId, UUID lockToken, String lastError, Instant now) {
        Row row = held(payoutId, lockToken);
        if (row == null) {
            return false;
        }
        row.status = "FAILED";
        row.lockedAt = null;
        row.lockToken = null;
        row.nextAttemptAt = null;
        row.lastError = PayoutClaimRepository.truncateLastError(lastError);
        return true;
    }

    /** The two arms of the claim predicate: due and unheld, or holding a stale lock. */
    private boolean isClaimable(Row row, Instant now) {
        if ("PENDING".equals(row.status)) {
            return row.nextAttemptAt == null || !row.nextAttemptAt.isAfter(now);
        }
        return "PROCESSING".equals(row.status)
                && row.lockedAt != null
                && row.lockedAt.isBefore(now.minus(staleLock));
    }

    /**
     * Every transition out of a claim is guarded by <em>this</em> claim still
     * holding the row, which is the token and not just the status. A row whose
     * stale lock another worker reclaimed is still PROCESSING, so matching on
     * status alone would let a superseded worker write over the holder.
     */
    private Row held(UUID payoutId, UUID lockToken) {
        Row row = rows.get(payoutId);
        if (row == null || !"PROCESSING".equals(row.status)) {
            return null;
        }
        return lockToken != null && lockToken.equals(row.lockToken) ? row : null;
    }
}
