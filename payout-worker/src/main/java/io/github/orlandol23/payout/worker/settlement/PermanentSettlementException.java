package io.github.orlandol23.payout.worker.settlement;

/**
 * The settlement will never happen, however many times it is tried.
 *
 * <p>A rejected account, an unsupported currency, a validation failure at the
 * provider: the 4xx shaped half of the taxonomy. Retrying is not merely useless
 * here, it is harmful, because it delays the payout reaching a human by the
 * whole backoff schedule before saying what it could have said immediately.
 */
public class PermanentSettlementException extends SettlementException {

    public PermanentSettlementException(String message) {
        super(message);
    }

    public PermanentSettlementException(String message, Throwable cause) {
        super(message, cause);
    }
}
