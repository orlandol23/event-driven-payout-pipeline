package io.github.orlandol23.payout.worker.settlement;

/**
 * Whatever actually moves the money.
 *
 * <p>An interface with one implementation, which is usually a smell and is not
 * one here: it is the seam the whole retry design is tested through. The
 * processor's behaviour depends entirely on what this throws, so the tests need
 * to make it throw on demand, and the day a real provider arrives it implements
 * this and nothing above it changes.
 *
 * <p>The contract is the exception types. An implementation that swallows a
 * timeout and returns normally has told the pipeline a payout was settled when
 * it was not, and no amount of care further up recovers from that.
 */
public interface SettlementGateway {

    /**
     * Settles one payout, or throws.
     *
     * @throws TransientSettlementException the attempt failed for a reason that
     *                                      may not be there next time
     * @throws PermanentSettlementException the attempt failed for a reason that
     *                                      will still be there next time
     */
    void settle(SettlementInstruction instruction);
}
