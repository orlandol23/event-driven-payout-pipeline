package io.github.orlandol23.payout.worker.settlement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The knobs on the simulated provider.
 *
 * <p>Properties rather than constants, so the failure modes are injectable: a
 * test drives each path by setting these instead of by knowing the magic
 * amounts, and a reviewer can move them if 13 and 66 happen to collide with the
 * amounts they wanted to demonstrate.
 *
 * @param latency          how long a settlement call takes. Zero in tests, a
 *                         couple of hundred milliseconds locally so the pipeline
 *                         behaves like something with a network in it
 * @param transientCents   an amount whose last two cent digits are this fails
 *                         transiently and is retried
 * @param permanentCents   an amount whose last two cent digits are this fails
 *                         permanently and is dead lettered immediately
 * @param rejectedCurrency a currency that is always rejected. Defaults to XTS,
 *                         the code ISO 4217 reserves for testing, so it passes
 *                         the API's currency validation and fails at settlement
 *                         rather than at the edge
 */
@ConfigurationProperties("payout.worker.settlement")
public record SettlementProperties(
        @DefaultValue("200ms") Duration latency,
        @DefaultValue("13") int transientCents,
        @DefaultValue("66") int permanentCents,
        @DefaultValue("XTS") String rejectedCurrency) {
}
