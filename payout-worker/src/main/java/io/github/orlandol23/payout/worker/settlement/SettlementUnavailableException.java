package io.github.orlandol23.payout.worker.settlement;

/** The settlement provider could not be reached, or would not answer in time. */
public class SettlementUnavailableException extends TransientSettlementException {

    public SettlementUnavailableException(String message) {
        super(message);
    }
}
