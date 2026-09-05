package io.github.orlandol23.payout.worker.settlement;

/**
 * The settlement did not happen, and the reason might not be there next time.
 *
 * <p>Timeouts, connection resets, a provider answering 503, a rate limit. The
 * request was well formed; the world was not ready for it. These are retried on
 * the backoff schedule until the attempt budget runs out.
 */
public class TransientSettlementException extends SettlementException {

    public TransientSettlementException(String message) {
        super(message);
    }

    public TransientSettlementException(String message, Throwable cause) {
        super(message, cause);
    }
}
