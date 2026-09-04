package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.worker.config.WorkerProperties;
import io.github.orlandol23.payout.worker.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Sweeps the table for work Kafka did not deliver, or delivered too early.
 *
 * <p><strong>This is the class that makes the topic optional.</strong> Three
 * kinds of payout are invisible to the listener and only this scan can find
 * them:
 *
 * <ul>
 *   <li><strong>A publish that was lost.</strong> The API commits the row and
 *       then publishes; with no outbox, a broker that is down between the two
 *       leaves a {@code PENDING} row and no event. The README has always said
 *       the claim scan is what bounds that gap. This is the claim scan.</li>
 *   <li><strong>A retry that has come due.</strong> A transient failure sets
 *       {@code next_attempt_at} minutes out. Nothing publishes a record when a
 *       timestamp passes, so somebody has to look.</li>
 *   <li><strong>A lock whose worker died.</strong> A row left {@code PROCESSING}
 *       is invisible to a redelivery, because the claim refuses a fresh lock.
 *       After the stale-lock timeout this reclaims it.</li>
 * </ul>
 *
 * <p>Which is the whole argument for Kafka being a latency optimisation here
 * rather than the source of truth: without the broker the pipeline still
 * settles every payout, just up to one poll interval later.
 *
 * <p>Sequentially, one row at a time, on purpose. The batch is small, settlement
 * is IO bound and the worker scales by adding instances that take different
 * partitions and different batches, not by fanning one batch across threads
 * inside one instance. Parallelism here would buy a little throughput and cost
 * the ability to reason about how many settlements are in flight at once.
 */
@Component
@ConditionalOnProperty(name = "payout.worker.poll-enabled", havingValue = "true", matchIfMissing = true)
public class PayoutPoller {

    private static final Logger log = LoggerFactory.getLogger(PayoutPoller.class);

    private final PayoutClaimRepository repository;
    private final PayoutProcessor processor;
    private final WorkerProperties properties;
    private final Clock clock;

    public PayoutPoller(PayoutClaimRepository repository,
                        PayoutProcessor processor,
                        WorkerProperties properties,
                        Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Claims and settles one batch.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}. The delay is measured from
     * the end of the previous scan, so a batch that takes longer than the
     * interval delays the next one instead of stacking scans on top of each
     * other, which is how a slow provider turns a backlog into a stampede.
     *
     * <p>The initial delay is one interval, so a starting worker is not scanning
     * the table while its consumer is still joining the group. Every payout it
     * would find is one the listener is about to be handed anyway.
     */
    @Scheduled(fixedDelayString = "${payout.worker.poll-interval}",
            initialDelayString = "${payout.worker.poll-interval}")
    public void claimDuePayouts() {
        List<ClaimedPayout> due;
        try {
            due = repository.claimDue(properties.pollBatchSize(), Instant.now(clock));
        } catch (RuntimeException failure) {
            // A scan that cannot reach the database must not kill the scheduled
            // task. Nothing is lost by failing here: every row it would have
            // claimed is still exactly where it was, and the next scan finds it.
            log.error("Claim scan failed; the rows it would have claimed are unchanged", failure);
            return;
        }

        if (due.isEmpty()) {
            return;
        }

        log.info("Claim scan picked up {} payout(s): due retries, stale locks, or events that were never published",
                due.size());

        for (ClaimedPayout claimed : due) {
            // The correlation id comes off the row rather than a record header,
            // because there is no record: these payouts are here precisely
            // because nothing was delivered for them. The log line still joins
            // up with the HTTP request that created the payout.
            MDC.put(CorrelationId.MDC_KEY, CorrelationId.resolve(claimed.correlationId()));
            try {
                processor.process(claimed);
            } catch (RuntimeException failure) {
                // One payout that blows up must not abandon the rest of the
                // batch still locked. This row keeps its lock until the stale
                // timeout and is reclaimed then.
                log.error("Settling claimed payout {} failed", claimed.id(), failure);
            } finally {
                MDC.remove(CorrelationId.MDC_KEY);
            }
        }
    }
}
