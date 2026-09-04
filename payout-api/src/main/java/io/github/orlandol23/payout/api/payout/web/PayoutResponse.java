package io.github.orlandol23.payout.api.payout.web;

import io.github.orlandol23.payout.api.payout.Payout;
import io.github.orlandol23.payout.api.payout.PayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Representation returned by {@code POST /payouts} and {@code GET /payouts/{id}}.
 *
 * <p>A separate type from the entity, not the entity itself. Serialising the
 * entity would tie the public contract to the database schema, so a column
 * rename would become a breaking API change, and it would risk lazy loading
 * inside the Jackson writer. Mapping explicitly also means new columns are
 * private until someone decides to expose them.
 *
 * <p>{@code attempts} and {@code lastError} are exposed from day 1 even though
 * only the worker writes them, so the contract does not change when it does.
 * {@code nextAttemptAt} joins them on day 3: a caller polling a payout that is
 * being retried should be able to see when the next attempt is due rather than
 * guessing at the backoff schedule. {@code lockedAt} stays private, because
 * which worker holds a row for how long is an implementation detail of the
 * queue, not something a client should build on.
 */
public record PayoutResponse(
        UUID id,
        BigDecimal amount,
        String currency,
        PayoutStatus status,
        String correlationId,
        int attempts,
        String lastError,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt) {

    public static PayoutResponse from(Payout payout) {
        return new PayoutResponse(
                payout.getId(),
                payout.getAmount(),
                payout.getCurrency(),
                payout.getStatus(),
                payout.getCorrelationId(),
                payout.getAttempts(),
                payout.getLastError(),
                payout.getNextAttemptAt(),
                payout.getCreatedAt(),
                payout.getUpdatedAt());
    }
}
