package io.github.orlandol23.payout.api.payout.web;

import io.github.orlandol23.payout.api.payout.Payout;
import io.github.orlandol23.payout.api.validation.Iso4217Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Body of {@code POST /payouts}.
 *
 * <p>A record: the request body is immutable data with no behaviour, which is
 * exactly what records are for, and it removes the getters, constructor,
 * {@code equals} and {@code hashCode} that a class would need. Jackson binds
 * records through the canonical constructor.
 *
 * <p>The idempotency key is <em>not</em> here. It is an {@code Idempotency-Key}
 * header, following the convention used across the payments industry, because
 * it describes how to process the request rather than what is being requested.
 */
public record CreatePayoutRequest(

        @NotNull(message = "amount is required")
        // Smallest representable positive amount at the stored scale. Expressed
        // as a lower bound rather than @Positive so that 0.00001 is rejected as
        // out of range instead of being rounded into existence.
        @DecimalMin(value = "0.0001", message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = Payout.AMOUNT_SCALE,
                message = "amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Iso4217Currency
        String currency) {
}
