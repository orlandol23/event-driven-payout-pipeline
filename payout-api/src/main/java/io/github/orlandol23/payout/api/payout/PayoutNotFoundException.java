package io.github.orlandol23.payout.api.payout;

import java.util.UUID;

/**
 * Thrown when a payout id does not resolve to a row.
 *
 * <p>Carries no HTTP status annotation on purpose. Mapping domain failures to
 * status codes is the exception handler's job, so this class stays usable from
 * the worker, which has no HTTP layer at all.
 */
public class PayoutNotFoundException extends RuntimeException {

    private final UUID payoutId;

    public PayoutNotFoundException(UUID payoutId) {
        super("Payout %s was not found".formatted(payoutId));
        this.payoutId = payoutId;
    }

    public UUID getPayoutId() {
        return payoutId;
    }
}
