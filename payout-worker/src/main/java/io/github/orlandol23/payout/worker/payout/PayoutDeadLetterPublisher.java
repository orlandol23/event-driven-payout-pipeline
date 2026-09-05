package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import io.github.orlandol23.payout.worker.correlation.CorrelationId;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Publishes payouts that will never settle to {@code payout.requested.dlt}.
 *
 * <p>The record body is the original {@code payout.requested} event, unchanged.
 * A dead letter that reshapes the message into some bespoke failure envelope
 * cannot be replayed onto the original topic, and replay is the only reason to
 * keep the messages at all. The diagnosis goes in headers instead, where a
 * consumer can filter on it without deserialising a body it may not understand.
 *
 * <p>Four headers, each answering a question whoever drains this topic will
 * otherwise ask: which request was this ({@code X-Correlation-Id}), was it
 * rejected or did it run out of attempts ({@code X-Failure-Kind}), what did the
 * provider say ({@code X-Failure-Reason}), and how many times did we try
 * ({@code X-Attempts}).
 */
@Component
public class PayoutDeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(PayoutDeadLetterPublisher.class);

    /** {@code permanent} or {@code exhausted}. See {@link DeadLetterReason}. */
    public static final String FAILURE_KIND_HEADER = "X-Failure-Kind";

    /** The last error, as recorded on the row. Already truncated. */
    public static final String FAILURE_REASON_HEADER = "X-Failure-Reason";

    /** How many attempts the payout consumed before it was given up on. */
    public static final String ATTEMPTS_HEADER = "X-Attempts";

    private final KafkaTemplate<String, PayoutRequested> kafkaTemplate;

    public PayoutDeadLetterPublisher(KafkaTemplate<String, PayoutRequested> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends one dead letter, keyed by payout id like the original event.
     *
     * <p>The same key for the same reason: every record about one payout stays
     * on one partition and in order, so a replay tool reading this topic sees a
     * payout's history the way it happened.
     *
     * <p>The send is asynchronous and a failure is logged rather than thrown.
     * The row is already {@code FAILED} by the time this runs, which is the
     * answer a caller polling {@code GET /payouts/{id}} gets either way; losing
     * the dead letter loses the alert, not the outcome. That is the same gap the
     * API has without an outbox, it is bounded the same way, and it is named in
     * the README rather than discovered here.
     */
    public void publish(PayoutRequested event, DeadLetterReason reason, String failureReason, int attempts) {
        ProducerRecord<String, PayoutRequested> record = new ProducerRecord<>(
                PayoutTopics.PAYOUT_REQUESTED_DLT,
                event.payoutId().toString(),
                event);
        header(record, CorrelationId.HEADER, event.correlationId());
        header(record, FAILURE_KIND_HEADER, reason.wireValue());
        header(record, FAILURE_REASON_HEADER, failureReason);
        header(record, ATTEMPTS_HEADER, String.valueOf(attempts));

        kafkaTemplate.send(record).whenComplete((result, failure) -> {
            if (failure != null) {
                log.error("Payout {} is FAILED but its {} record was not published [correlationId={}]",
                        event.payoutId(), PayoutTopics.PAYOUT_REQUESTED_DLT, event.correlationId(), failure);
                return;
            }
            log.warn("Dead lettered payout {} to {} after {} attempt(s): {}",
                    event.payoutId(), PayoutTopics.PAYOUT_REQUESTED_DLT, attempts, failureReason);
        });
    }

    /** Headers are bytes on the wire; null values are simply not written. */
    private static void header(ProducerRecord<String, PayoutRequested> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
