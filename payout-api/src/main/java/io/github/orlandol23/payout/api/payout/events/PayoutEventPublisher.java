package io.github.orlandol23.payout.api.payout.events;

import io.github.orlandol23.payout.api.correlation.CorrelationId;
import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Publishes payout events to Kafka.
 *
 * <p>One class, one topic, no branching: the service hands it an event and it
 * puts that event on the wire. Everything interesting about it is in what it
 * chooses to do with the key, the header and a failure.
 */
@Component
public class PayoutEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PayoutEventPublisher.class);

    private final KafkaTemplate<String, PayoutRequested> kafkaTemplate;

    public PayoutEventPublisher(KafkaTemplate<String, PayoutRequested> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Sends a {@code payout.requested} event, keyed by payout id.
     *
     * <p><strong>The key is the payout id, and that is the whole ordering
     * guarantee.</strong> Kafka partitions by key, so every event about one
     * payout lands on one partition and is delivered to one consumer in the
     * order it was produced. Round-robin keys would let a retry event overtake
     * the request that caused it, and would let two workers hold two events for
     * the same payout at the same time.
     *
     * <p>The correlation id travels twice: in the body, because a consumer that
     * deserialises the event should not have to look elsewhere for it, and in an
     * {@code X-Correlation-Id} record header, because a consumer that cannot
     * deserialise the event still has to be able to say which request produced
     * the poison message.
     *
     * <p>The send is asynchronous. The returned future is not awaited, because
     * an HTTP request must not block on a broker; instead the failure path is
     * logged from the callback, and the caller is expected to have made the row
     * durable first. See {@code PayoutService.create} for why that ordering is
     * safe and where it is still lossy.
     */
    public void publish(PayoutRequested event) {
        ProducerRecord<String, PayoutRequested> record = new ProducerRecord<>(
                PayoutTopics.PAYOUT_REQUESTED,
                event.payoutId().toString(),
                event);
        record.headers().add(CorrelationId.HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).whenComplete((result, failure) -> {
            if (failure != null) {
                // ERROR, not WARN: the row is durable but nothing is listening
                // for it until day 3's claim scan, so this is a real delay to a
                // real payout and someone should be paged for a burst of them.
                log.error("Failed to publish {} for payout {} [correlationId={}]",
                        PayoutTopics.PAYOUT_REQUESTED, event.payoutId(), event.correlationId(), failure);
                return;
            }
            log.info("Published {} for payout {} to partition {} at offset {}",
                    PayoutTopics.PAYOUT_REQUESTED,
                    event.payoutId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        });
    }
}
