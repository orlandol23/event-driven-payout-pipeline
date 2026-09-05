package io.github.orlandol23.payout.api.payout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What "the same request" means, in one place.
 *
 * <p>The interesting assertions are the two normalisation cases. If
 * {@code 125.5} and {@code 125.5000} produced different fingerprints, every
 * client that trims trailing zeros would get a 422 on a retry that was, by any
 * sane reading, identical.
 */
class IdempotencyFingerprintTest {

    @Test
    @DisplayName("the same request always fingerprints the same")
    void isDeterministic() {
        String first = IdempotencyFingerprint.of(new BigDecimal("125.5000"), "BRL");
        String second = IdempotencyFingerprint.of(new BigDecimal("125.5000"), "BRL");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("it is 64 hex characters, which is what the column is sized for")
    void isHexOfTheRightWidth() {
        assertThat(IdempotencyFingerprint.of(new BigDecimal("1.0000"), "USD"))
                .hasSize(IdempotencyFingerprint.LENGTH)
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("trailing zeros do not change the request, so they do not change the fingerprint")
    void amountScaleIsNormalised() {
        assertThat(IdempotencyFingerprint.of(new BigDecimal("125.5"), "BRL"))
                .isEqualTo(IdempotencyFingerprint.of(new BigDecimal("125.5000"), "BRL"));
        assertThat(IdempotencyFingerprint.of(new BigDecimal("10"), "USD"))
                .isEqualTo(IdempotencyFingerprint.of(new BigDecimal("10.0000"), "USD"));
    }

    @Test
    @DisplayName("a different amount or a different currency is a different request")
    void differentRequestsDiffer() {
        String reference = IdempotencyFingerprint.of(new BigDecimal("125.5000"), "BRL");

        assertThat(IdempotencyFingerprint.of(new BigDecimal("125.5100"), "BRL")).isNotEqualTo(reference);
        assertThat(IdempotencyFingerprint.of(new BigDecimal("1255.0000"), "BRL")).isNotEqualTo(reference);
        assertThat(IdempotencyFingerprint.of(new BigDecimal("125.5000"), "USD")).isNotEqualTo(reference);
    }

    @Test
    @DisplayName("the separator keeps the amount from bleeding into the currency")
    void fieldsCannotBleedIntoEachOther() {
        // Concatenating without a separator would make 1.0000 + "0USD" and
        // 1.00000 + "USD" the same string. They are different requests, and one
        // of them is not even storable.
        assertThat(IdempotencyFingerprint.of(new BigDecimal("1.0000"), "USD"))
                .isNotEqualTo(IdempotencyFingerprint.of(new BigDecimal("1.0000"), "0USD"));
    }

    @Test
    @DisplayName("an amount the column cannot store is a bug, not a fingerprint")
    void unstorableAmountsThrow() {
        // Same rule as Payout.request: rounding a payment into existence to
        // compute a hash of it would be the worst possible place to do it.
        assertThatThrownBy(() -> IdempotencyFingerprint.of(new BigDecimal("1.00001"), "USD"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("a payout carries the fingerprint of the request that created it")
    void aPayoutFingerprintsItsOwnRequest() {
        Payout payout = Payout.request(UUID.randomUUID(), "key-1", new BigDecimal("125.5"), "BRL",
                "corr-1", java.time.Instant.parse("2026-07-27T10:15:30Z"));

        assertThat(payout.getIdempotencyFingerprint())
                .isEqualTo(IdempotencyFingerprint.of(new BigDecimal("125.5000"), "BRL"));
    }
}
