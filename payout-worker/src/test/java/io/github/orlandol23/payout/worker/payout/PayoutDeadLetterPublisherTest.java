package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import io.github.orlandol23.payout.worker.correlation.CorrelationId;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What actually goes on the dead letter topic.
 *
 * <p>A mocked {@link KafkaTemplate} rather than a broker, because the assertions
 * here are about the record: its topic, its key, its body and its four headers.
 * That such a record reaches a real topic is proven against an embedded broker
 * in {@code PayoutRequestedListenerTest}.
 */
@ExtendWith(MockitoExtension.class)
class PayoutDeadLetterPublisherTest {

    private static final UUID PAYOUT_ID = UUID.randomUUID();
    private static final String CORRELATION_ID = "corr-abc-123";

    private static final PayoutRequested EVENT = new PayoutRequested(
            PAYOUT_ID,
            new BigDecimal("125.5000"),
            "BRL",
            CORRELATION_ID,
            Instant.parse("2026-07-27T10:15:30Z"));

    @Mock
    private KafkaTemplate<String, PayoutRequested> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, PayoutRequested>> recordCaptor;

    private PayoutDeadLetterPublisher publisher;

    @BeforeEach
    void createPublisher() {
        publisher = new PayoutDeadLetterPublisher(kafkaTemplate);
    }

    @Test
    @DisplayName("publishes the original event to payout.requested.dlt, keyed by payout id")
    void publishesTheOriginalEvent() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());

        publisher.publish(EVENT, DeadLetterReason.PERMANENT, "SettlementRejectedException: account closed", 1);

        ProducerRecord<String, PayoutRequested> record = captureRecord();
        assertThat(record.topic()).isEqualTo(PayoutTopics.PAYOUT_REQUESTED_DLT);
        // The same key as the original topic, so one payout's records stay on
        // one partition and in order for whatever eventually drains this.
        assertThat(record.key()).isEqualTo(PAYOUT_ID.toString());
        // The body is untouched. A dead letter that reshapes the message cannot
        // be replayed onto the topic it came from, and replay is the only reason
        // to keep it.
        assertThat(record.value()).isEqualTo(EVENT);
    }

    @Test
    @DisplayName("a permanent failure is labelled permanent, with the reason and the attempt count")
    void permanentHeaders() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());

        publisher.publish(EVENT, DeadLetterReason.PERMANENT, "SettlementRejectedException: account closed", 1);

        ProducerRecord<String, PayoutRequested> record = captureRecord();
        assertThat(headerOf(record, PayoutDeadLetterPublisher.FAILURE_KIND_HEADER)).isEqualTo("permanent");
        assertThat(headerOf(record, PayoutDeadLetterPublisher.FAILURE_REASON_HEADER))
                .isEqualTo("SettlementRejectedException: account closed");
        assertThat(headerOf(record, PayoutDeadLetterPublisher.ATTEMPTS_HEADER)).isEqualTo("1");
        assertThat(headerOf(record, CorrelationId.HEADER)).isEqualTo(CORRELATION_ID);
    }

    @Test
    @DisplayName("an exhausted payout is labelled exhausted, which is a different problem to diagnose")
    void exhaustedHeaders() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(sent());

        publisher.publish(EVENT, DeadLetterReason.EXHAUSTED, "SettlementUnavailableException: down", 5);

        ProducerRecord<String, PayoutRequested> record = captureRecord();
        // A rejected request needs someone to look at the request; an exhausted
        // one needs someone to look at the provider. Collapsing both into "it
        // failed" hands whoever drains this topic a pile to re-diagnose.
        assertThat(headerOf(record, PayoutDeadLetterPublisher.FAILURE_KIND_HEADER)).isEqualTo("exhausted");
        assertThat(headerOf(record, PayoutDeadLetterPublisher.ATTEMPTS_HEADER)).isEqualTo("5");
    }

    @Test
    @DisplayName("a broker failure does not escape, because the row is already FAILED")
    void aBrokerFailureIsLoggedNotThrown() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("no broker available")));

        // Losing the dead letter loses the alert, not the outcome: the payout is
        // FAILED either way, and that is what GET /payouts/{id} answers. Same
        // gap the API has without an outbox, named in the README.
        assertThatCode(() -> publisher.publish(EVENT, DeadLetterReason.PERMANENT, "rejected", 1))
                .doesNotThrowAnyException();
    }

    private ProducerRecord<String, PayoutRequested> captureRecord() {
        org.mockito.Mockito.verify(kafkaTemplate).send(recordCaptor.capture());
        return recordCaptor.getValue();
    }

    private static CompletableFuture<SendResult<String, PayoutRequested>> sent() {
        return CompletableFuture.completedFuture(null);
    }

    private static String headerOf(ProducerRecord<String, PayoutRequested> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
