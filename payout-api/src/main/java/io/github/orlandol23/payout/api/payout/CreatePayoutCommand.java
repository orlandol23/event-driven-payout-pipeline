package io.github.orlandol23.payout.api.payout;

import java.math.BigDecimal;

/**
 * What the service needs to create a payout, expressed without any HTTP types.
 *
 * <p>Keeping this separate from the request DTO means the transport can change,
 * and on day 2 a Kafka consumer can call the same service, without the domain
 * layer learning about headers or status codes.
 *
 * @param idempotencyKey optional; null means the caller opted out of replay protection
 * @param correlationId  never null; resolved at the edge before this is built
 */
public record CreatePayoutCommand(
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String correlationId) {
}
