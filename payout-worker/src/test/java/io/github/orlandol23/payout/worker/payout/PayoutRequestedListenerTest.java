package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import io.github.orlandol23.payout.worker.correlation.CorrelationId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The whole worker against a real broker, running in this JVM.
 *
 * <p>{@code @EmbeddedKafka} rather than Testcontainers, so this runs on a machine
 * with no Docker. The whole worker context starts, which means the
 * deserializers, the error handler and the acknowledgement mode all come from
 * {@code application.yml} rather than from settings invented here. A listener
 * that only works under test configuration is not evidence of anything.
 *
 * <p>The one substitution is storage: an in-memory {@link PayoutClaimRepository}
 * stands in for PostgreSQL, so the claim, the settlement, the retry decision and
 * the dead letter are all the production code and only the table is not. What
 * the claim statement does under real concurrency is a database question and
 * belongs to {@code PayoutWorkerIT}.
 */
@SpringBootTest(properties = {
        // The simulated provider's latency is realism this test does not need.
        "payout.worker.settlement.latency=0ms",
        // This test is about the Kafka path. The claim scan would race it for
        // the same rows and prove nothing about the listener either way.
        "payout.worker.poll-enabled=false"
})
@EmbeddedKafka(partitions = 1, topics = {PayoutTopics.PAYOUT_REQUESTED, PayoutTopics.PAYOUT_REQUESTED_DLT})
class PayoutRequestedListenerTest {

    private static final String CORRELATION_ID = "corr-abc-123";
    private static final Duration LISTENER_TIMEOUT = Duration.ofSeconds(30);
    private static final Instant REQUESTED_AT = Instant.parse("2026-07-27T10:15:30Z");
    private static final Duration STALE_LOCK = Duration.ofMinutes(5);

    /**
     * Replaces the JDBC repository with a map.
     *
     * <p>{@code @Primary} rather than excluding the real bean: the JDBC one is
     * still constructed, which keeps this test honest about the context starting
     * the way production does, it just never gets injected anywhere.
     */
    @TestConfiguration
    static class InMemoryStorage {

        @Bean
        @Primary
        InMemoryPayoutClaimRepository inMemoryPayoutClaimRepository() {
            return new InMemoryPayoutClaimRepository(STALE_LOCK);
        }
    }

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private InMemoryPayoutClaimRepository payouts;

    /**
     * A spy, not a mock: the real listener still runs, still claims and still
     * acknowledges. This only makes "did it receive that record" observable,
     * without adding a latch to production code that exists solely for a test.
     */
    @MockitoSpyBean
    private PayoutRequestedListener listener;

    private DefaultKafkaProducerFactory<String, PayoutRequested> eventProducers;
    private DefaultKafkaProducerFactory<String, String> rawProducers;
    private KafkaTemplate<String, PayoutRequested> events;
    private KafkaTemplate<String, String> rawBytes;
    private Consumer<String, PayoutRequested> deadLetters;

    @BeforeEach
    void createProducersAndWaitForTheListener() {
        eventProducers = producerFactory(new JsonSerializer<>());
        rawProducers = producerFactory(new StringSerializer());
        events = new KafkaTemplate<>(eventProducers);
        rawBytes = new KafkaTemplate<>(rawProducers);
        deadLetters = deadLetterConsumer();

        // Publishing into a partition the container has not been assigned yet is
        // how this kind of test goes intermittently red.
        await().atMost(LISTENER_TIMEOUT).until(() -> registry.getListenerContainers().stream()
                .allMatch(container -> container.isRunning()
                        && container.getAssignedPartitions() != null
                        && !container.getAssignedPartitions().isEmpty()));
    }

    @AfterEach
    void closeClients() {
        eventProducers.destroy();
        rawProducers.destroy();
        deadLetters.close();
    }

    @Test
    @DisplayName("claims the row, settles it and commits the offset, which is what acknowledgement means")
    void settlesAndAcknowledges() throws Exception {
        long committedBefore = committedOffset();
        UUID payoutId = pendingPayout(new BigDecimal("125.5000"), "BRL");

        send(event(payoutId, new BigDecimal("125.5000"), "BRL"));

        await().atMost(LISTENER_TIMEOUT).untilAsserted(() -> {
            assertThat(payouts.row(payoutId).status).isEqualTo("CONFIRMED");
            assertThat(payouts.row(payoutId).attempts).isEqualTo(1);
            assertThat(payouts.row(payoutId).lockedAt).as("a settled payout holds no lock").isNull();
        });

        // The committed offset is the second assertion. A listener that did the
        // work and never acknowledged leaves the group behind the end of the log,
        // and the same payout comes back on the next restart.
        await().atMost(LISTENER_TIMEOUT).untilAsserted(() ->
                assertThat(committedOffset())
                        .as("the consumer group's committed offset advanced")
                        .isGreaterThan(committedBefore));
    }

    /**
     * The thesis of the whole design, on the Kafka path.
     *
     * <p>The same record twice is what a rebalance, a redelivery or a duplicate
     * producer retry looks like. The second claim finds a {@code CONFIRMED} row,
     * matches neither arm of the predicate and returns nothing, so the second
     * delivery settles nothing.
     */
    @Test
    @DisplayName("a redelivered event is a no-op, not a second payment")
    void redeliveryDoesNoWorkTwice() {
        UUID payoutId = pendingPayout(new BigDecimal("42.0000"), "USD");
        PayoutRequested event = event(payoutId, new BigDecimal("42.0000"), "USD");

        send(event);
        send(event);

        verify(listener, timeout(LISTENER_TIMEOUT.toMillis()).times(2))
                .onPayoutRequested(any(), any(), any());

        assertThat(payouts.row(payoutId).status).isEqualTo("CONFIRMED");
        assertThat(payouts.row(payoutId).attempts)
                .as("the second delivery claimed nothing, so it counted no attempt")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a permanently rejected payout is failed and dead lettered with the diagnosis in headers")
    void permanentFailureIsDeadLettered() {
        // .66 is the simulated provider's permanent rejection. See the failure
        // mode table in the README.
        UUID payoutId = pendingPayout(new BigDecimal("10.6600"), "USD");

        send(event(payoutId, new BigDecimal("10.6600"), "USD"));

        await().atMost(LISTENER_TIMEOUT).untilAsserted(() ->
                assertThat(payouts.row(payoutId).status).isEqualTo("FAILED"));
        assertThat(payouts.row(payoutId).attempts)
                .as("a permanent failure is not retried")
                .isEqualTo(1);
        assertThat(payouts.row(payoutId).lastError).contains("SettlementRejectedException");

        ConsumerRecord<String, PayoutRequested> record = KafkaTestUtils.getSingleRecord(
                deadLetters, PayoutTopics.PAYOUT_REQUESTED_DLT, LISTENER_TIMEOUT);

        assertThat(record.key()).isEqualTo(payoutId.toString());
        assertThat(record.value())
                .as("the body is the original event, so it can be replayed onto the original topic")
                .isEqualTo(event(payoutId, new BigDecimal("10.6600"), "USD"));
        assertThat(headerOf(record, PayoutDeadLetterPublisher.FAILURE_KIND_HEADER)).isEqualTo("permanent");
        assertThat(headerOf(record, PayoutDeadLetterPublisher.ATTEMPTS_HEADER)).isEqualTo("1");
        assertThat(headerOf(record, PayoutDeadLetterPublisher.FAILURE_REASON_HEADER))
                .contains("SettlementRejectedException");
        assertThat(headerOf(record, CorrelationId.HEADER)).isEqualTo(CORRELATION_ID);
    }

    @Test
    @DisplayName("a malformed payload is skipped, and the next valid record is still consumed")
    void aPoisonRecordDoesNotStopTheContainer() {
        rawBytes.send(new ProducerRecord<>(PayoutTopics.PAYOUT_REQUESTED,
                UUID.randomUUID().toString(),
                "this is not the event you are looking for"));

        UUID payoutId = pendingPayout(new BigDecimal("7.0000"), "EUR");
        PayoutRequested afterThePoison = event(payoutId, new BigDecimal("7.0000"), "EUR");
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

    private UUID pendingPayout(BigDecimal amount, String currency) {
        UUID payoutId = UUID.randomUUID();
        payouts.insertPending(payoutId, amount, currency, CORRELATION_ID, REQUESTED_AT);
        return payoutId;
    }

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

    private static String headerOf(ConsumerRecord<String, PayoutRequested> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Consumer<String, PayoutRequested> deadLetterConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-observer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JsonDeserializer<PayoutRequested> valueDeserializer = new JsonDeserializer<>(PayoutRequested.class);
        valueDeserializer.addTrustedPackages(PayoutRequested.class.getPackageName());

        Consumer<String, PayoutRequested> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer)
                        .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, true, PayoutTopics.PAYOUT_REQUESTED_DLT);
        return consumer;
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

    private static PayoutRequested event(UUID payoutId, BigDecimal amount, String currency) {
        return new PayoutRequested(payoutId, amount, currency, CORRELATION_ID, REQUESTED_AT);
    }
}
