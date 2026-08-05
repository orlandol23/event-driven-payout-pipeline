package io.github.orlandol23.payout.api.payout;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    /**
     * Looks up the payout previously created for an idempotency key.
     *
     * <p>Backed by {@code ux_payouts_idempotency_key}. This is a convenience for
     * the happy path and for recovering after losing the insert race; it is not
     * what makes the operation safe. The unique index is.
     */
    Optional<Payout> findByIdempotencyKey(String idempotencyKey);
}
