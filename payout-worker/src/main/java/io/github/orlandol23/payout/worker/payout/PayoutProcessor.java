package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.worker.config.WorkerProperties;
import io.github.orlandol23.payout.worker.settlement.ErrorClassifier;
import io.github.orlandol23.payout.worker.settlement.FailureKind;
import io.github.orlandol23.payout.worker.settlement.SettlementGateway;
import io.github.orlandol23.payout.worker.settlement.SettlementInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim, settle, and decide what a failure means.
 *
 * <p>All the pipeline's judgement lives here, and nothing else does. The
 * listener knows about Kafka and not about retries; the poller knows about the
 * schedule and not about settlement; the repository knows about SQL. This class
 * is where a payout's outcome is chosen, which is why it is the one the unit
 * tests spend their time on.
 *
 * <p>The four outcomes:
 *
 * <ul>
 *   <li><strong>Nothing to claim.</strong> Another worker has it, it is already
 *       terminal, or a retry is not due. Debug and return: a redelivered event
 *       is supposed to be a no-op.</li>
 *   <li><strong>Settled.</strong> {@code CONFIRMED}, lock released.</li>
 *   <li><strong>Transient failure with attempts left.</strong> Back to
 *       {@code PENDING} with {@code next_attempt_at} set by the backoff.</li>
 *   <li><strong>Permanent failure, or the last attempt.</strong> {@code FAILED}
 *       and dead lettered, with the two cases distinguished on the record.</li>
 * </ul>
 */
@Component
public class PayoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(PayoutProcessor.class);

    private final PayoutClaimRepository repository;
    private final SettlementGateway settlementGateway;
    private final ErrorClassifier errorClassifier;
    private final BackoffSchedule backoffSchedule;
    private final PayoutDeadLetterPublisher deadLetterPublisher;
    private final WorkerProperties properties;
    private final Clock clock;

    public PayoutProcessor(PayoutClaimRepository repository,
                           SettlementGateway settlementGateway,
                           ErrorClassifier errorClassifier,
                           BackoffSchedule backoffSchedule,
                           PayoutDeadLetterPublisher deadLetterPublisher,
                           WorkerProperties properties,
                           Clock clock) {
        this.repository = repository;
        this.settlementGateway = settlementGateway;
        this.errorClassifier = errorClassifier;
        this.backoffSchedule = backoffSchedule;
        this.deadLetterPublisher = deadLetterPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Tries to take a payout and, if it gets it, sees it through.
     *
     * <p>The entry point for an event off {@code payout.requested}. Losing the
     * claim is the expected case, not an error: it is what a redelivery, a
     * duplicate, or a row the claim scan already picked up looks like from here.
     *
     * @return whether this call actually did any work
     */
    public boolean claimAndProcess(UUID payoutId) {
        Optional<ClaimedPayout> claimed = repository.claim(payoutId, Instant.now(clock));
        if (claimed.isEmpty()) {
            // DEBUG, not WARN. On a healthy pipeline this fires for every
            // redelivery and every row the poller got to first, and a log line
            // that cries wolf on the normal case is a log line people filter out
            // before it ever says something true.
            log.debug("Payout {} was not claimable: already settled, held by another worker, or not due yet",
                    payoutId);
            return false;
        }
        process(claimed.get());
        return true;
    }

    /**
     * Settles a payout this worker already holds.
     *
     * <p>Public because the claim scan claims in batches and then hands each row
     * here, rather than claiming twice.
     */
    public void process(ClaimedPayout claimed) {
        if (claimed.attempts() > properties.maxAttempts()) {
            // Only reachable by reclaiming a stale lock on a payout that had
            // already spent its budget: the worker holding it died after the
            // final attempt was counted and before it could record the outcome.
            // Settling again would be an attempt past the limit, which for money
            // is the one thing the limit exists to prevent.
            log.warn("Payout {} was reclaimed with {} attempts against a budget of {}; failing without settling",
                    claimed.id(), claimed.attempts(), properties.maxAttempts());
            giveUp(claimed, DeadLetterReason.EXHAUSTED,
                    "Attempt budget of %d was already spent when the stale lock was reclaimed"
                            .formatted(properties.maxAttempts()));
            return;
        }

        log.info("Settling payout {} for {} {}, attempt {} of {}",
                claimed.id(), claimed.amount(), claimed.currency(),
                claimed.attempts(), properties.maxAttempts());

        try {
            settlementGateway.settle(new SettlementInstruction(
                    claimed.id(), claimed.amount(), claimed.currency(), claimed.correlationId()));
        } catch (RuntimeException failure) {
            handleFailure(claimed, failure);
            return;
        }

        repository.confirm(claimed.id(), Instant.now(clock));
        log.info("Confirmed payout {} on attempt {}", claimed.id(), claimed.attempts());
    }

    /**
     * Turns a settlement failure into the next state of the row.
     *
     * <p>{@link RuntimeException} rather than the settlement types, on purpose.
     * A gateway that throws something unforeseen, a driver, a serialisation
     * error, is exactly the case {@link ErrorClassifier} exists for, and letting
     * it escape here would leave the payout {@code PROCESSING} under a lock that
     * nothing releases until the stale-lock timeout.
     */
    private void handleFailure(ClaimedPayout claimed, RuntimeException failure) {
        FailureKind kind = errorClassifier.classify(failure);
        String reason = describe(failure);

        if (kind == FailureKind.PERMANENT) {
            log.warn("Payout {} failed permanently on attempt {}: {}",
                    claimed.id(), claimed.attempts(), reason, failure);
            giveUp(claimed, DeadLetterReason.PERMANENT, reason);
            return;
        }

        if (claimed.attempts() >= properties.maxAttempts()) {
            log.warn("Payout {} exhausted its {} attempts, last failure: {}",
                    claimed.id(), properties.maxAttempts(), reason, failure);
            giveUp(claimed, DeadLetterReason.EXHAUSTED, reason);
            return;
        }

        Instant now = Instant.now(clock);
        Instant nextAttemptAt = backoffSchedule.nextAttemptAt(claimed.attempts(), now);
        repository.scheduleRetry(claimed.id(), nextAttemptAt, reason, now);
        log.warn("Payout {} failed transiently on attempt {} of {}, next attempt at {}: {}",
                claimed.id(), claimed.attempts(), properties.maxAttempts(), nextAttemptAt, reason, failure);
    }

    /**
     * Terminal failure: the row first, the dead letter second.
     *
     * <p>That order matters. Marking the row {@code FAILED} first means the
     * payout can never be claimed again, so a crash between the two costs an
     * alert and not a settlement attempt on a payout that is already finished.
     * The reverse order would leave a {@code PROCESSING} row with a stale lock
     * that the scan would eventually reclaim and try to settle again.
     */
    private void giveUp(ClaimedPayout claimed, DeadLetterReason reason, String lastError) {
        repository.fail(claimed.id(), lastError, Instant.now(clock));
        deadLetterPublisher.publish(claimed.toEvent(), reason, lastError, claimed.attempts());
    }

    /**
     * What goes in {@code last_error}: the exception type and its message,
     * truncated to the column.
     *
     * <p>The type is included because the message alone is often a bare
     * "Connection reset" that says nothing about which layer produced it. The
     * stack trace is not, because it belongs in the log, under the correlation
     * id, where it can be read in full and expires on the log's retention.
     */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String described = message == null
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
        return PayoutClaimRepository.truncateLastError(described);
    }
}
