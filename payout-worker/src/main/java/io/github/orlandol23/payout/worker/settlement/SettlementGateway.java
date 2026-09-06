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
 *
 * <h2>Idempotency is part of the contract</h2>
 *
 * <p>An implementation MUST treat {@link SettlementInstruction#payoutId()} as an
 * idempotency key: a second call carrying a payout id it has already settled
 * moves no further money and returns normally.
 *
 * <p>This is not a nicety, it is where the pipeline's exactly-once claim
 * actually lives. Everything upstream is arranged so a payout is settled at most
 * once per claim, and none of it can promise at most once per <em>payout</em>,
 * because the worker can die in the window between the provider accepting the
 * money and the row recording that it did. That row is then reclaimed on the
 * stale-lock timeout and settled again, by design, since the alternative is
 * dropping payouts whose outcome is merely unknown. What makes the second call
 * harmless is this key and nothing else.
 *
 * <p>So the honest shape of the guarantee is: <strong>at-least-once delivery to
 * the provider, exactly-once effect, provided the provider deduplicates on the
 * payout id.</strong> Every real settlement API worth integrating offers such a
 * key; an implementation wrapping one that does not has to build the ledger to
 * supply it, and must say so rather than let this javadoc speak for it.
 */
public interface SettlementGateway {

    /**
     * Settles one payout, or throws.
     *
     * <p>Calling this twice with the same {@link SettlementInstruction#payoutId()}
     * must be indistinguishable from calling it once.
     *
     * @throws TransientSettlementException the attempt failed for a reason that
     *                                      may not be there next time
     * @throws PermanentSettlementException the attempt failed for a reason that
     *                                      will still be there next time
     */
    void settle(SettlementInstruction instruction);
}
