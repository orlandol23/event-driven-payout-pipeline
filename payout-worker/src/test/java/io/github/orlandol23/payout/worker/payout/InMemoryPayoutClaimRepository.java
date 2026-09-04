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
        return Optional.of(new ClaimedPayout(
                payoutId, row.amount, row.currency, row.correlationId, row.attempts, row.createdAt));
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
    public synchronized void confirm(UUID payoutId, Instant now) {
        Row row = processing(payoutId);
        if (row == null) {
            return;
        }
        row.status = "CONFIRMED";
        row.lockedAt = null;
        row.nextAttemptAt = null;
    }

    @Override
    public synchronized void scheduleRetry(UUID payoutId, Instant nextAttemptAt, String lastError, Instant now) {
        Row row = processing(payoutId);
        if (row == null) {
            return;
        }
        row.status = "PENDING";
        row.lockedAt = null;
        row.nextAttemptAt = nextAttemptAt;
        row.lastError = PayoutClaimRepository.truncateLastError(lastError);
    }

    @Override
    public synchronized void fail(UUID payoutId, String lastError, Instant now) {
        Row row = processing(payoutId);
        if (row == null) {
            return;
        }
        row.status = "FAILED";
        row.lockedAt = null;
        row.nextAttemptAt = null;
        row.lastError = PayoutClaimRepository.truncateLastError(lastError);
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

    /** Every transition out of a claim is guarded by the row still being held. */
    private Row processing(UUID payoutId) {
        Row row = rows.get(payoutId);
        return row != null && "PROCESSING".equals(row.status) ? row : null;
    }
}
