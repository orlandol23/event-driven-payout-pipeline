package io.github.orlandol23.payout.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Asynchronous half of the pipeline.
 *
 * <p>Day 1 scope is intentionally an empty shell: it builds, boots and answers
 * {@code /actuator/health}. That is enough to prove the multi-module build and
 * the container wiring end to end before there is any consumer logic to get
 * wrong.
 *
 * <p>What lands here next:
 * <ul>
 *   <li>day 2: a {@code @KafkaListener} on {@code payout.requested}, keyed by
 *       payout id, with manual acknowledgement</li>
 *   <li>day 3: the atomic claim, the transient versus permanent error taxonomy,
 *       exponential backoff and the dead letter topic</li>
 *   <li>day 4: metrics, and the correlation id read back out of the Kafka
 *       headers into the MDC</li>
 * </ul>
 */
@SpringBootApplication
public class PayoutWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutWorkerApplication.class, args);
    }
}
