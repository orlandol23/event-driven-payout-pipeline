package io.github.orlandol23.payout.worker.payout;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The worker's entire view of the {@code payouts} table.
 *
 * <p>Six statements, no entity, no persistence context. The API owns the schema
 * and the row's shape; the worker owns the transitions on it and reads exactly
 * the columns it needs to make them. That narrowness is the price of two
 * services sharing one database, and it is cheap only as long as it is actually
 * kept: the moment this interface grows a "find" or an "update everything",
 * the worker has an ORM again and the boundary is gone.
 *
 * <p>Every method takes the current instant instead of letting SQL call
 * {@code now()}. The timestamps that land in the row are then the ones the
 * injected {@link java.time.Clock} produced, so a test can fix time and assert
 * the exact {@code next_attempt_at} a backoff computed rather than a range
 * around it.
 *
 * <p>An interface, so the Kafka path can be proven against an in-memory
 * implementation on a machine with no Docker, and the decision logic can be
 * tested against a mock. The statements themselves are a database question and
 * are answered by {@code PayoutWorkerIT} against a real PostgreSQL.
 */
public interface PayoutClaimRepository {

    /**
     * Width of {@code payouts.last_error}. Mirrored from the migration, because
     * a truncation that guesses at the column width is a truncation that stops
     * working the day the column changes.
     */
    int LAST_ERROR_MAX_LENGTH = 2048;

    /**
     * Takes ownership of one payout, or reports that it could not.
     *
     * <p>The conditional {@code UPDATE} is the whole concurrency design. Two
     * workers handed the same event both run it; the database serialises them
     * and the second one updates zero rows, so it gets an empty result and
     * stops. No lock is held across the settlement call, nothing to time out,
     * and a redelivered event is a no-op rather than a second payment.
     *
     * <p>Returns empty when the payout is already {@code PROCESSING} under a
     * fresh lock, already terminal, or not due yet. All three mean the same
     * thing to the caller: there is nothing to do.
     *
     * <p>Every claim mints a fresh lock token and every transition is guarded by
     * it. Guarding on {@code status = 'PROCESSING'} alone is not enough, because
     * a row whose stale lock another worker just reclaimed is still
     * {@code PROCESSING}: the guard passes and the superseded worker overwrites
     * the holder's state.
     *
     * @param now the instant to stamp on the row, and the moment against which
     *            "due" and "stale" are judged
     */
    Optional<ClaimedPayout> claim(UUID payoutId, Instant now);

    /**
     * Claims a batch of whatever is due, oldest request first.
     *
     * <p>The same predicate as {@link #claim}, without an id. This is what makes
     * the table the source of truth rather than the topic: it finds retries that
     * have come due, locks whose worker died, and payouts whose
     * {@code payout.requested} event was never published because the broker was
     * down when the row was committed. Kafka gets those payouts settled in
     * milliseconds; this gets them settled at all.
     *
     * <p>Batched and ordered, so a backlog is worked through oldest first rather
     * than in whatever order the planner likes, and bounded so one scan cannot
     * lock the entire table.
     *
     * @param limit how many rows to take at most
     * @return the rows this call now holds, in the order they should be settled
     */
    List<ClaimedPayout> claimDue(int limit, Instant now);

    /**
     * Re-stamps the lock this claim holds, proving it is still the holder.
     *
     * <p>The claim scan takes a batch of rows in one statement and settles them
     * one after another, so every row in the batch carries the same
     * {@code locked_at} while the last one may not be reached for as long as the
     * batch takes. With the default batch of 50 and a five minute stale-lock
     * window, six seconds a settlement is enough for the tail of a batch to
     * become reclaimable while this worker is still holding it and about to call
     * the provider.
     *
     * <p>Renewing immediately before the settlement call closes that: the lock is
     * measured from when the row is actually worked on rather than from when its
     * batch was claimed, and a renewal that updates nothing is this worker being
     * told, before it spends any money, that the row is no longer its own.
     *
     * @return {@code false} when the lock was already reclaimed, in which case
     *         the caller must not settle
     */
    boolean renewLock(UUID payoutId, UUID lockToken, Instant now);

    /**
     * Marks a settled payout {@code CONFIRMED} and releases the lock. Terminal.
     *
     * @return {@code false} when this claim no longer held the row, meaning the
     *         update was rejected and the settlement it was recording is now
     *         unrecorded. The caller has to say so out loud: this is money that
     *         moved with nothing in the table to show for it
     */
    boolean confirm(UUID payoutId, UUID lockToken, Instant now);

    /**
     * Returns a payout to {@code PENDING}, due again at {@code nextAttemptAt}.
     *
     * <p>The lock is released here, not kept until the retry: a row waiting for
     * its next attempt is not being worked on, and a lock that outlives the work
     * it protects is indistinguishable from a leaked one.
     *
     * @return {@code false} when this claim no longer held the row
     */
    boolean scheduleRetry(UUID payoutId, UUID lockToken, Instant nextAttemptAt, String lastError, Instant now);

    /**
     * Marks a payout {@code FAILED} and releases the lock. Terminal.
     *
     * @return {@code false} when this claim no longer held the row
     */
    boolean fail(UUID payoutId, UUID lockToken, String lastError, Instant now);

    /**
     * Cuts a failure message down to what the column can hold.
     *
     * <p>Truncation rather than a wider column, and named here rather than left
     * to the database to do silently. {@code last_error} exists to say what went
     * wrong, not to archive a stack trace: the trace belongs in the log, where
     * it can be searched by correlation id and expires on the log's retention
     * rather than growing the payouts table forever.
     */
    static String truncateLastError(String lastError) {
        if (lastError == null || lastError.length() <= LAST_ERROR_MAX_LENGTH) {
            return lastError;
        }
        return lastError.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
