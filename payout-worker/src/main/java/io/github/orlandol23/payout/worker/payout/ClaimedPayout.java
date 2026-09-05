package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payout row this worker now holds, as the claim statement returned it.
 *
 * <p>Everything needed to settle the payout and to decide what to do when
 * settlement fails, read in the same statement that took the lock. A claim
 * followed by a SELECT would be two round trips and, worse, a window in which
 * the row can change between the two.
 *
 * @param attempts    the count <em>after</em> this claim incremented it, so a
 *                    freshly claimed payout reads 1 on its first attempt. The
 *                    retry policy compares this against the attempt budget
 * @param requestedAt when the API accepted the request. Carried so a dead
 *                    lettered payout can be rebuilt into the event that
 *                    requested it, rather than into one that claims it was
 *                    requested at the moment it failed
 */
public record ClaimedPayout(
        UUID id,
        BigDecimal amount,
        String currency,
        String correlationId,
        int attempts,
        Instant requestedAt) {

    /**
     * Rebuilds the {@code payout.requested} event this row came from.
     *
     * <p>Rebuilt from the row rather than forwarded from the consumer, and that
     * is deliberate. The row is the source of truth; the Kafka record is a
     * latency optimisation that may never have arrived at all. A payout the
     * claim scan picked up has no incoming record to forward, and a dead letter
     * that only exists for payouts whose event survived would be a dead letter
     * topic with a hole in exactly the case it was built for.
     */
    public PayoutRequested toEvent() {
        return new PayoutRequested(id, amount, currency, correlationId, requestedAt);
    }
}
