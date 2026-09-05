package io.github.orlandol23.payout.api.payout;

import java.util.UUID;

/**
 * Thrown when an {@code Idempotency-Key} comes back with a different request
 * behind it.
 *
 * <p>This is not a replay and it is not a conflict with concurrent state: the
 * key was used once, for one request, and is now being used for another. Both
 * possible answers without this exception are wrong. Creating a second payout
 * breaks the promise the key made; returning the first one hands the caller a
 * payout for an amount it did not ask for, which is the failure mode that turns
 * into a support ticket six weeks later.
 *
 * <p>Carries the ids rather than a formatted message for the client, because the
 * response deliberately does not echo either one back. See
 * {@code GlobalExceptionHandler}.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    private final String idempotencyKey;
    private final UUID existingPayoutId;

    public IdempotencyKeyReusedException(String idempotencyKey, UUID existingPayoutId) {
        super("Idempotency key %s was already used for payout %s with a different request"
                .formatted(idempotencyKey, existingPayoutId));
        this.idempotencyKey = idempotencyKey;
        this.existingPayoutId = existingPayoutId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getExistingPayoutId() {
        return existingPayoutId;
    }
}
