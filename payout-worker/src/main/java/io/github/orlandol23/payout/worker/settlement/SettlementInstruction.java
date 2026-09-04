package io.github.orlandol23.payout.worker.settlement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a settlement provider would need to move this money.
 *
 * <p>Separate from the claimed row so the gateway never sees the queue: it has
 * no attempt count, no status and no lock, because a provider integration has
 * no business branching on how many times we have asked it before.
 *
 * @param payoutId      ours, not the provider's, and the natural idempotency key
 *                      to hand a real provider on the day there is one
 * @param correlationId travels with the instruction so a provider's logs and
 *                      ours can be lined up over the same request
 */
public record SettlementInstruction(
        UUID payoutId,
        BigDecimal amount,
        String currency,
        String correlationId) {
}
