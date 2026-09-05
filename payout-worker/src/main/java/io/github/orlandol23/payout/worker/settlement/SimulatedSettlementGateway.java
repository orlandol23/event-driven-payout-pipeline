package io.github.orlandol23.payout.worker.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * A settlement provider that moves no money and fails on demand.
 *
 * <p><strong>No money moves anywhere in this repository.</strong> What this
 * class provides is the one thing a real provider cannot: a deterministic way to
 * reach every branch of the retry policy from a {@code curl} command, which is
 * what makes the transient, permanent and exhausted paths demonstrable instead
 * of merely written down.
 *
 * <p>The trigger is the amount, because the amount is the one field a caller
 * controls freely and the API already validates. The last two digits of the cent
 * value select the outcome:
 *
 * <table border="1">
 *   <caption>Failure modes</caption>
 *   <tr><th>Request</th><th>Outcome</th><th>What the pipeline does</th></tr>
 *   <tr><td>amount ending in .13</td><td>transient</td>
 *       <td>retried on the backoff schedule, dead lettered when attempts run out</td></tr>
 *   <tr><td>amount ending in .66</td><td>permanent</td>
 *       <td>failed and dead lettered immediately, with no retry</td></tr>
 *   <tr><td>currency XTS</td><td>permanent</td>
 *       <td>same, and reachable without picking a special amount</td></tr>
 *   <tr><td>anything else</td><td>success</td><td>CONFIRMED after the configured latency</td></tr>
 * </table>
 *
 * <p>Deterministic rather than random on purpose. A gateway that fails one call
 * in ten makes a test that passes most of the time, which is worse than no test:
 * it is a test nobody trusts and everybody reruns.
 */
@Component
public class SimulatedSettlementGateway implements SettlementGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSettlementGateway.class);

    /** Cents, so the trigger is written the way an amount is: two decimal places. */
    private static final int CENT_SCALE = 2;
    private static final BigInteger ONE_HUNDRED = BigInteger.valueOf(100);

    private final SettlementProperties properties;

    public SimulatedSettlementGateway(SettlementProperties properties) {
        this.properties = properties;
    }

    @Override
    public void settle(SettlementInstruction instruction) {
        pause(properties.latency());

        if (properties.rejectedCurrency().equalsIgnoreCase(instruction.currency())) {
            throw new SettlementRejectedException(
                    "Currency %s is not supported by this provider".formatted(instruction.currency()));
        }

        int cents = lastCentDigits(instruction.amount());
        if (cents == properties.transientCents()) {
            throw new SettlementUnavailableException(
                    "Settlement provider is unavailable, try again later (simulated for .%02d)".formatted(cents));
        }
        if (cents == properties.permanentCents()) {
            throw new SettlementRejectedException(
                    "Settlement was rejected by the provider (simulated for .%02d)".formatted(cents));
        }

        log.info("Settled payout {} for {} {} (simulated)",
                instruction.payoutId(), instruction.amount(), instruction.currency());
    }

    /**
     * The cent part of the amount, 0 to 99.
     *
     * <p>{@code RoundingMode.DOWN} rather than rejecting sub-cent precision: the
     * column stores four decimal places and the trigger is about the cents, so
     * 125.1399 is a .13 amount. Truncating is right here and would be wrong in
     * anything that touched real money, which is why this class is the only
     * place it happens.
     */
    private static int lastCentDigits(BigDecimal amount) {
        return amount.setScale(CENT_SCALE, RoundingMode.DOWN)
                .unscaledValue()
                .abs()
                .mod(ONE_HUNDRED)
                .intValue();
    }

    /**
     * Stands in for the network round trip a real provider would cost.
     *
     * <p>It matters for more than realism: without it the stale lock timeout and
     * the poll interval would never overlap with a settlement in flight, and the
     * lock recovery path would be untested by construction. The interrupt is
     * restored and re-reported as transient, because a worker shutting down
     * mid-settlement has not settled anything and the payout must stay claimable.
     */
    private static void pause(Duration latency) {
        if (latency.isZero() || latency.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SettlementUnavailableException("Interrupted while settling");
        }
    }
}
