package io.github.orlandol23.payout.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code payout-api} once a payout row is durable, consumed by
 * {@code payout-worker}.
 *
 * <p>A record, so the shape is the constructor and there is no way to build a
 * half-populated event. Jackson binds records through their canonical
 * constructor, so this needs no annotations and this module needs no Jackson
 * dependency at compile scope.
 *
 * <p>The fields are the minimum a consumer needs to find and settle the payout,
 * and nothing more. In particular there is no status and no attempt count: those
 * live on the row, they change after the event is written, and copying them onto
 * the wire would put a stale value in front of a consumer that has the real one
 * a query away.
 *
 * <p>{@code payoutId} is also the partition key. Same key, same partition, same
 * order: every event about one payout is handled in the order it was produced,
 * and two workers can never process one payout concurrently.
 *
 * @param payoutId      primary key of the {@code payouts} row this event is about
 * @param amount        the payout amount, at the scale the row stores it
 * @param currency      ISO 4217 alphabetic code, upper case
 * @param correlationId ties the event to the HTTP request that caused it; also
 *                      travels in the {@code X-Correlation-Id} record header so a
 *                      consumer can read it without deserialising the body
 * @param requestedAt   when the API accepted the request, not when it published
 */
public record PayoutRequested(
        UUID payoutId,
        BigDecimal amount,
        String currency,
        String correlationId,
        Instant requestedAt) {
}
