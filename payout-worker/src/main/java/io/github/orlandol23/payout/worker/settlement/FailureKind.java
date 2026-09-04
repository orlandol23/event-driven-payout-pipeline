package io.github.orlandol23.payout.worker.settlement;

/**
 * The two answers the retry policy needs from a failure.
 *
 * <p>Two values and no third. "Maybe" would have to be resolved somewhere, and
 * the only place it could be resolved is the code that has to decide whether to
 * try again.
 */
public enum FailureKind {

    /** Worth another attempt, on the backoff schedule, until the budget runs out. */
    TRANSIENT,

    /** Never worth another attempt. Fail the payout now and dead letter it. */
    PERMANENT
}
