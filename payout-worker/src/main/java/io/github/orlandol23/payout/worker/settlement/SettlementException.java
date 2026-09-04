package io.github.orlandol23.payout.worker.settlement;

/**
 * Root of the settlement error taxonomy.
 *
 * <p>Abstract on purpose: nothing throws "a settlement failure". Every failure
 * is either {@link TransientSettlementException}, which is worth trying again,
 * or {@link PermanentSettlementException}, which never is. That distinction is
 * the whole retry policy, and making it impossible to throw a failure without
 * choosing a side is what keeps it from being forgotten.
 *
 * <p>Unchecked, because a caller that cannot settle a payout cannot recover
 * locally either. The one place that knows what to do with a failure is
 * {@code PayoutProcessor}, and it catches this on purpose rather than because a
 * signature forced it to.
 */
public abstract class SettlementException extends RuntimeException {

    protected SettlementException(String message) {
        super(message);
    }

    protected SettlementException(String message, Throwable cause) {
        super(message, cause);
    }
}
