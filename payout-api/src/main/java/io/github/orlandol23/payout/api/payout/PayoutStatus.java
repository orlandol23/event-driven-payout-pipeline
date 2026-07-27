package io.github.orlandol23.payout.api.payout;

/**
 * Lifecycle of a payout.
 *
 * <p>Persisted as text, never as an ordinal: an ordinal column silently
 * corrupts every existing row the day someone reorders this enum.
 *
 * <pre>
 *   PENDING --claim--&gt; PROCESSING --success--&gt; CONFIRMED
 *                          |
 *                          +--permanent failure, or retries exhausted--&gt; FAILED
 * </pre>
 *
 * <p>Only {@code PENDING} rows are claimable, which is what makes reprocessing
 * the same event a no-op instead of a double payment. The transitions out of
 * {@code PROCESSING} are implemented by the worker on day 3.
 */
public enum PayoutStatus {

    /** Accepted and durable, not yet picked up by a worker. */
    PENDING,

    /** Claimed by exactly one worker. */
    PROCESSING,

    /** Settled successfully. Terminal. */
    CONFIRMED,

    /** Rejected permanently, or transient retries were exhausted. Terminal. */
    FAILED;

    /** A terminal payout is never claimed, retried or mutated again. */
    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED;
    }
}
