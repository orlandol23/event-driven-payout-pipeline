package io.github.orlandol23.payout.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Asynchronous half of the pipeline.
 *
 * <p>Day 2 scope: it consumes {@code payout.requested} with manual
 * acknowledgement, logs each event under the correlation id the API created it
 * with, and answers {@code /actuator/health}. It settles nothing yet, and says
 * so rather than pretending otherwise.
 *
 * <p>What lands here next:
 * <ul>
 *   <li>day 3: the atomic claim, the transient versus permanent error taxonomy,
 *       exponential backoff and the {@code payout.requested.dlt} dead letter
 *       topic the contracts module already names</li>
 *   <li>day 4: metrics, and structured logs carrying the correlation id this
 *       listener already puts in the MDC</li>
 * </ul>
 */
@SpringBootApplication
public class PayoutWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutWorkerApplication.class, args);
    }
}
