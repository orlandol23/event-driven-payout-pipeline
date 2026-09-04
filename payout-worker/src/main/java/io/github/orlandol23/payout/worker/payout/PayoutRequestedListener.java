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
 * Consumes {@code payout.requested}.
 *
 * <p><strong>Day 2 does one thing: it reads the event, logs it and
 * acknowledges.</strong> No claim, no settlement, no retry. That is not an
 * oversight, it is the point of splitting the work: this commit proves the
 * transport, the contract and the acknowledgement, so day 3 can add settlement
 * to a pipe that is already known to be correct.
 *
 * <p>The acknowledgement is manual and immediate
 * ({@code spring.kafka.listener.ack-mode: manual_immediate}). Automatic commits
 * would advance the offset on a timer, independently of whether this method ever
 * ran, so a worker that crashed mid-settlement could come back to a payout Kafka
 * believes was handled. Manual acknowledgement makes the offset mean "the
 * listener finished", which is the only meaning worth having once there is real
 * work here.
 *
 * <p>The correlation id is taken from the record header rather than the body, so
 * it is available even for a record whose body did not deserialise, and it is
 * put in the MDC for the duration of the record so every line logged about this
 * payout carries the id the HTTP request that created it was logged under.
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
     * to every worker, which for payouts is the duplicate payment the whole
     * design exists to prevent.
     *
     * <p>Declared here rather than in {@code application.yml} so the listener and
     * its group are one thing to read and one thing to change.
     */
    static final String GROUP_ID = "payout-worker";

    @KafkaListener(topics = PayoutTopics.PAYOUT_REQUESTED, groupId = GROUP_ID)
    public void onPayoutRequested(
            @Payload PayoutRequested event,
            @Header(name = CorrelationId.HEADER, required = false) byte[] correlationIdHeader,
            Acknowledgment acknowledgment) {

        MDC.put(CorrelationId.MDC_KEY, correlationIdFrom(correlationIdHeader));
        try {
            log.info("Received {} for payout {}: {} {} requested at {}",
                    PayoutTopics.PAYOUT_REQUESTED,
                    event.payoutId(),
                    event.amount(),
                    event.currency(),
                    event.requestedAt());

            // Day 3 puts the atomic claim and the settlement call here. The
            // acknowledgement stays where it is, after the work, because that is
            // what makes the committed offset honest.
            acknowledgment.acknowledge();
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private static String correlationIdFrom(byte[] header) {
        String candidate = header == null ? null : new String(header, StandardCharsets.UTF_8);
        return CorrelationId.resolve(candidate);
    }
}
