package io.github.orlandol23.payout.api.payout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");

    @Test
    @DisplayName("a requested payout always starts PENDING with no attempts and no error")
    void startsInPending() {
        Payout payout = request(new BigDecimal("42.0000"));

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING);
        assertThat(payout.getAttempts()).isZero();
        assertThat(payout.getLastError()).isNull();
        assertThat(payout.getCreatedAt()).isEqualTo(NOW);
        assertThat(payout.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("rejects an amount that cannot be stored without losing precision")
    void rejectsUnstorableAmount() {
        // Five decimal places cannot be held by numeric(19,4). Failing here beats
        // rounding a payment silently.
        assertThatThrownBy(() -> request(new BigDecimal("1.00001")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("only CONFIRMED and FAILED are terminal")
    void terminalStates() {
        assertThat(PayoutStatus.PENDING.isTerminal()).isFalse();
        assertThat(PayoutStatus.PROCESSING.isTerminal()).isFalse();
        assertThat(PayoutStatus.CONFIRMED.isTerminal()).isTrue();
        assertThat(PayoutStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("equality is the id, so a payout stays findable in a set after its status moves on")
    void equalityIsTheId() {
        UUID id = UUID.randomUUID();
        Payout one = Payout.request(id, "k1", new BigDecimal("1.0000"), "USD", "c1", NOW);
        Payout same = Payout.request(id, "k1", new BigDecimal("1.0000"), "USD", "c1", NOW);
        Payout other = request(new BigDecimal("1.0000"));

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isNotEqualTo(other);
    }

    private static Payout request(BigDecimal amount) {
        return Payout.request(UUID.randomUUID(), null, amount, "USD", "corr-1", NOW);
    }
}
