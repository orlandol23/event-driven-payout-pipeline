package io.github.orlandol23.payout.worker.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The failure modes a reviewer is invited to trigger with curl.
 *
 * <p>Every branch documented in the README's failure mode table is asserted
 * here, so the table cannot quietly stop being true.
 */
class SimulatedSettlementGatewayTest {

    private static final UUID PAYOUT_ID = UUID.randomUUID();
    private static final String CORRELATION_ID = "corr-abc-123";

    /** Zero latency: this is about which branch is taken, not how slowly. */
    private final SimulatedSettlementGateway gateway = new SimulatedSettlementGateway(
            new SettlementProperties(Duration.ZERO, 13, 66, "XTS"));

    @Test
    @DisplayName("an ordinary amount settles")
    void ordinaryAmountsSettle() {
        assertThatCode(() -> gateway.settle(instruction("125.5000", "BRL"))).doesNotThrowAnyException();
        assertThatCode(() -> gateway.settle(instruction("0.0100", "USD"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an amount ending in .13 fails transiently, so it is retried")
    void thirteenCentsIsTransient() {
        assertThatThrownBy(() -> gateway.settle(instruction("125.1300", "BRL")))
                .isInstanceOf(SettlementUnavailableException.class)
                .isInstanceOf(TransientSettlementException.class);
    }

    @Test
    @DisplayName("an amount ending in .66 fails permanently, so it is dead lettered at once")
    void sixtySixCentsIsPermanent() {
        assertThatThrownBy(() -> gateway.settle(instruction("10.6600", "USD")))
                .isInstanceOf(SettlementRejectedException.class)
                .isInstanceOf(PermanentSettlementException.class);
    }

    @Test
    @DisplayName("the trigger is the cents, whatever the rest of the amount is")
    void theTriggerIsTheCentsAlone() {
        assertThatThrownBy(() -> gateway.settle(instruction("999999.1300", "USD")))
                .isInstanceOf(TransientSettlementException.class);
        // numeric(19,4) holds more than cents; the sub-cent digits are truncated
        // rather than rounded, so .1399 is still a .13 amount.
        assertThatThrownBy(() -> gateway.settle(instruction("1.1399", "USD")))
                .isInstanceOf(TransientSettlementException.class);
        assertThatCode(() -> gateway.settle(instruction("1.1299", "USD"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("XTS is rejected, so a permanent failure is reachable without a special amount")
    void testCurrencyIsRejected() {
        // XTS is the code ISO 4217 reserves for testing, so it passes the API's
        // currency validation and fails here instead of at the edge.
        assertThatThrownBy(() -> gateway.settle(instruction("100.0000", "XTS")))
                .isInstanceOf(SettlementRejectedException.class)
                .hasMessageContaining("XTS");
    }

    @Test
    @DisplayName("the failure modes are injectable, not baked in")
    void triggersAreConfigurable() {
        SimulatedSettlementGateway rebound = new SimulatedSettlementGateway(
                new SettlementProperties(Duration.ZERO, 7, 8, "ZWL"));

        assertThatThrownBy(() -> rebound.settle(instruction("1.0700", "USD")))
                .isInstanceOf(TransientSettlementException.class);
        assertThatThrownBy(() -> rebound.settle(instruction("1.0800", "USD")))
                .isInstanceOf(PermanentSettlementException.class);
        assertThatCode(() -> rebound.settle(instruction("1.1300", "USD")))
                .as("the old trigger is no longer a trigger")
                .doesNotThrowAnyException();
    }

    private static SettlementInstruction instruction(String amount, String currency) {
        return new SettlementInstruction(PAYOUT_ID, new BigDecimal(amount), currency, CORRELATION_ID);
    }
}
