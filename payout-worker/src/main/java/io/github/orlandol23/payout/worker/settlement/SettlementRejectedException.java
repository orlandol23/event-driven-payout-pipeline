package io.github.orlandol23.payout.worker.settlement;

/** The settlement provider understood the instruction and refused it. */
public class SettlementRejectedException extends PermanentSettlementException {

    public SettlementRejectedException(String message) {
        super(message);
    }
}
