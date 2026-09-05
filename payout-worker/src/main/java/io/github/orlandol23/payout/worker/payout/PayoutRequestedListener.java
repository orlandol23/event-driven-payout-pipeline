package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import io.github.orlandol23.payout.worker.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumes {@code payout.requested} and hands the payout to the processor.
 *
 * <p><strong>The event is a nudge, not an instruction.</strong> All it carries
 * that this listener uses is the payout id; everything the pipeline acts on is
 * read from the row by the claim, because the row is the source of truth and the
 * event may be a redelivery, a duplicate, or minutes stale. That is what makes
 * the second delivery of a record a no-op rather than a second payment.
 *
 * <p>The acknowledgement is manual and immediate, and it happens exactly once
 * per record, on every path. A failure here is not retried by leaving the offset
 * where it was: the row is still {@code PROCESSING} or {@code PENDING} and the
 * claim scan will pick it up, whereas an unacknowledged record comes back
 * immediately, fails the same way, and turns one broken payout into a consumer
 * that stops making progress on the others.
 *
 * <p>The correlation id comes from the record header rather than the body, so it
 * is available even for a record that did not deserialise, and it is in the MDC
 * for the whole handling: every line the settlement writes carries the id of the
 * HTTP request that created the payout.
 */
@Component
public class PayoutRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(PayoutRequestedListener.class);

    /**
     * Consumer group id, and the unit of scale.
     *
     * <p>Every worker instance joins this one group, so Kafka hands each
     * partition to exactly one of them and adding instances divides the work
     * instead of duplicating it. A per-instance group would deliver every event
     * to every worker; the claim would still stop the double payment, but every
     * instance but one would do nothing except lose a race.
     *
     * <p>Declared here rather than in {@code application.yml} so the listener and
     * its group are one thing to read and one thing to change.
     */
    static final String GROUP_ID = "payout-worker";

    private final PayoutProcessor processor;

    public PayoutRequestedListener(PayoutProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = PayoutTopics.PAYOUT_REQUESTED, groupId = GROUP_ID)
    public void onPayoutRequested(
            @Payload PayoutRequested event,
            @Header(name = CorrelationId.HEADER, required = false) byte[] correlationIdHeader,
            Acknowledgment acknowledgment) {

        MDC.put(CorrelationId.MDC_KEY, correlationIdFrom(correlationIdHeader));
        try {
            log.info("Received {} for payout {}", PayoutTopics.PAYOUT_REQUESTED, event.payoutId());
            processor.claimAndProcess(event.payoutId());
        } catch (RuntimeException failure) {
            // Logged and swallowed rather than rethrown. The payout is safe
            // either way: its row is still claimable, or still locked until the
            // stale-lock timeout, and the scan is what recovers it. Rethrowing
            // would replay this record until it succeeds, which for a payout the
            // database cannot be reached for means never.
            log.error("Handling {} for payout {} failed; the row is still the source of truth",
                    PayoutTopics.PAYOUT_REQUESTED, event.payoutId(), failure);
        } finally {
            acknowledgment.acknowledge();
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static String correlationIdFrom(byte[] header) {
        String candidate = header == null ? null : new String(header, StandardCharsets.UTF_8);
        return CorrelationId.resolve(candidate);
    }
}
