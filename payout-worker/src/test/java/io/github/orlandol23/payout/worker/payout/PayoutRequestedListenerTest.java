package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import io.github.orlandol23.payout.worker.correlation.CorrelationId;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The listener against a real broker, running in this JVM.
 *
 * <p>{@code @EmbeddedKafka} rather than Testcontainers, so this runs on a machine
 * with no Docker. The whole worker context starts, which means the
 * deserializers, the error handler and the acknowledgement mode all come from
 * {@code application.yml} rather than from settings invented here. A listener
 * that only works under test configuration is not evidence of anything.
 *
 * <p>Two claims are worth proving on day 2, and they are the two tests below:
 * that a published event reaches the listener and its offset is committed, and
 * that a record the deserializer cannot read does not take the container down
 * with it.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = PayoutTopics.PAYOUT_REQUESTED)
class PayoutRequestedListenerTest {

    private static final String CORRELATION_ID = "corr-abc-123";
    private static final Duration LISTENER_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    /**
     * A spy, not a mock: the real listener still runs and still acknowledges.
     * This only makes "did it receive that record" observable, without adding a
     * latch to production code that exists solely for a test.
     */
    @MockitoSpyBean
    private PayoutRequestedListener listener;

    private DefaultKafkaProducerFactory<String, PayoutRequested> eventProducers;
    private DefaultKafkaProducerFactory<String, String> rawProducers;
    private KafkaTemplate<String, PayoutRequested> events;
    private KafkaTemplate<String, String> rawBytes;

    @BeforeEach
    void createProducersAndWaitForTheListener() {
        eventProducers = producerFactory(new JsonSerializer<>());
        rawProducers = producerFactory(new StringSerializer());
        events = new KafkaTemplate<>(eventProducers);
        rawBytes = new KafkaTemplate<>(rawProducers);

        // Publishing into a partition the container has not been assigned yet is
        // how this kind of test goes intermittently red.
        await().atMost(LISTENER_TIMEOUT).until(() -> registry.getListenerContainers().stream()
                .allMatch(container -> container.isRunning()
                        && container.getAssignedPartitions() != null
                        && !container.getAssignedPartitions().isEmpty()));
    }

    @AfterEach
    void closeProducers() {
        eventProducers.destroy();
        rawProducers.destroy();
    }

    @Test
    @DisplayName("consumes an event and commits its offset, which is what acknowledgement means")
    void consumesAndAcknowledges() throws Exception {
        long committedBefore = committedOffset();
        PayoutRequested event = event(UUID.randomUUID());

        send(event);

        ArgumentCaptor<PayoutRequested> received = ArgumentCaptor.forClass(PayoutRequested.class);
        verify(listener, timeout(LISTENER_TIMEOUT.toMillis()))
                .onPayoutRequested(received.capture(), any(), any());
        assertThat(received.getValue())
                .as("the event crossed the wire unchanged, amount scale included")
                .isEqualTo(event);

        // The committed offset is the real assertion. A listener that received a
        // record and never acknowledged leaves the group behind the end of the
        // log, and the same payout comes back on the next restart.
        await().atMost(LISTENER_TIMEOUT).untilAsserted(() ->
                assertThat(committedOffset())
                        .as("the consumer group's committed offset advanced")
                        .isGreaterThan(committedBefore));
    }

    @Test
    @DisplayName("a malformed payload is skipped, and the next valid record is still consumed")
    void aPoisonRecordDoesNotStopTheContainer() {
        rawBytes.send(new ProducerRecord<>(PayoutTopics.PAYOUT_REQUESTED,
                UUID.randomUUID().toString(),
                "this is not the event you are looking for"));

        PayoutRequested afterThePoison = event(UUID.randomUUID());
        send(afterThePoison);

        ArgumentCaptor<PayoutRequested> received = ArgumentCaptor.forClass(PayoutRequested.class);
        verify(listener, timeout(LISTENER_TIMEOUT.toMillis()).atLeastOnce())
                .onPayoutRequested(received.capture(), any(), any());

        assertThat(received.getAllValues())
                .as("the record after the poison one was still delivered")
                .contains(afterThePoison);
        assertThat(registry.getListenerContainers())
                .as("the container is still running rather than dead on a deserialization error")
                .allMatch(container -> container.isRunning());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void send(PayoutRequested event) {
        ProducerRecord<String, PayoutRequested> record = new ProducerRecord<>(
                PayoutTopics.PAYOUT_REQUESTED, event.payoutId().toString(), event);
        record.headers().add(CorrelationId.HEADER, CORRELATION_ID.getBytes(StandardCharsets.UTF_8));
        events.send(record);
    }

    /** Zero rather than null before the group has committed anything. */
    private long committedOffset() throws Exception {
        OffsetAndMetadata committed = KafkaTestUtils.getCurrentOffset(
                broker.getBrokersAsString(),
                PayoutRequestedListener.GROUP_ID,
                PayoutTopics.PAYOUT_REQUESTED,
                0);
        return committed == null ? 0L : committed.offset();
    }

    private <V> DefaultKafkaProducerFactory<String, V> producerFactory(Serializer<V> valueSerializer) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        // Same as the API's producer: the consumer is told the type by its own
        // configuration, so no __TypeId__ header goes on the wire. Sending one
        // here would test a contract we do not ship.
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    private static PayoutRequested event(UUID payoutId) {
        return new PayoutRequested(
                payoutId,
                new BigDecimal("125.5000"),
                "BRL",
                CORRELATION_ID,
                Instant.parse("2026-07-27T10:15:30Z"));
    }
}
