package io.github.orlandol23.payout.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Asynchronous half of the pipeline.
 *
 * <p>Day 3 scope: it claims payouts atomically, settles them through a simulated
 * gateway, retries transient failures on a backoff schedule and dead letters
 * everything that will never settle. It reaches the {@code payouts} table
 * through one repository of hand written statements and never through the API's
 * JPA entity, which is not a convention here but a fact of the build: there is
 * no JPA on this module's classpath.
 *
 * <p>{@code @ConfigurationPropertiesScan} rather than a list of
 * {@code @EnableConfigurationProperties}, so a new settings record is bound by
 * existing next to what reads it. {@code @EnableScheduling} is here for the
 * claim scan, which is what lets the worker settle payouts whose Kafka event
 * never arrived; {@code payout.worker.poll-enabled} turns it off for tests that
 * have no database.
 *
 * <p>What lands here next:
 * <ul>
 *   <li>day 4: metrics, and structured logs carrying the correlation id this
 *       listener already puts in the MDC</li>
 *   <li>later: a consumer for {@code payout.requested.dlt}. Today the topic
 *       collects failures and nothing drains it</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PayoutWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutWorkerApplication.class, args);
    }
}
