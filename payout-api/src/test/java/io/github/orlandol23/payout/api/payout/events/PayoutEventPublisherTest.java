package io.github.orlandol23.payout.api.payout.events;

import io.github.orlandol23.payout.api.correlation.CorrelationId;
import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer against a real broker, running in this JVM.
 *
 * <p>{@code @EmbeddedKafka} rather than Testcontainers, so this runs on a machine
 * with no Docker. The broker is a genuine KRaft broker with genuine serializers,
 * so what this asserts is what a consumer would actually receive: the key, the
 * bytes and the headers.
 *
 * <p>The context is deliberately Kafka and nothing else. It imports the real
 * {@link KafkaAutoConfiguration} so the producer is built from the
 * {@code spring.kafka.producer} block in {@code application.yml} rather than
 * from settings invented here. A serializer or an {@code acks} value that is
 * wrong in production is therefore wrong in this test too, which is the only way
 * a configuration test is worth writing. Nothing else is auto-configured, so
 * there is no DataSource to start and no schema to migrate.
 */
@SpringBootTest(classes = PayoutEventPublisherTest.KafkaOnlyContext.class)
@EmbeddedKafka(partitions = 1, topics = PayoutTopics.PAYOUT_REQUESTED)
class PayoutEventPublisherTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    @Import(PayoutEventPublisher.class)
    static class KafkaOnlyContext {
    }

    private static final String CORRELATION_ID = "corr-abc-123";

    @Autowired
    private PayoutEventPublisher publisher;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private Consumer<String, PayoutRequested> consumer;

    /**
     * A fresh consumer per test, seeked to the end of the topic before anything
     * is published, so each test reads only the record it produced and the tests
     * do not have to run in a particular order.
     */
    @BeforeEach
    void subscribe() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payout-event-publisher-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        JsonDeserializer<PayoutRequested> valueDeserializer = new JsonDeserializer<>(PayoutRequested.class);
        valueDeserializer.addTrustedPackages(PayoutRequested.class.getPackageName());

        consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer)
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, true, PayoutTopics.PAYOUT_REQUESTED);
    }

    @AfterEach
    void unsubscribe() {
        consumer.close();
    }

    @Test
    @DisplayName("the record key is the payout id, which is what pins one payout to one partition")
    void keysByPayoutId() {
        PayoutRequested event = event(UUID.randomUUID());

        publisher.publish(event);

        assertThat(consumed().key()).isEqualTo(event.payoutId().toString());
    }

    @Test
    @DisplayName("the body deserialises back to the event that was published")
    void bodySurvivesTheRoundTrip() {
        PayoutRequested event = event(UUID.randomUUID());

        publisher.publish(event);

        ConsumerRecord<String, PayoutRequested> record = consumed();
        assertThat(record.value()).isEqualTo(event);
        // Spelled out, because these are the two fields JSON quietly damages.
        assertThat(record.value().amount()).isEqualByComparingTo("125.5000");
        assertThat(record.value().requestedAt()).isEqualTo(event.requestedAt());
    }

    @Test
    @DisplayName("the correlation id is on the record header, readable without deserialising the body")
    void correlationIdTravelsInAHeader() {
        publisher.publish(event(UUID.randomUUID()));

        Header header = consumed().headers().lastHeader(CorrelationId.HEADER);

        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(CORRELATION_ID);
    }

    @Test
    @DisplayName("no __TypeId__ header, so the consumer is not coupled to our package layout")
    void doesNotShipTheJavaTypeOnTheWire() {
        publisher.publish(event(UUID.randomUUID()));

        assertThat(consumed().headers().lastHeader("__TypeId__")).isNull();
    }

    private ConsumerRecord<String, PayoutRequested> consumed() {
        return KafkaTestUtils.getSingleRecord(consumer, PayoutTopics.PAYOUT_REQUESTED, Duration.ofSeconds(15));
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
