package io.github.orlandol23.payout.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.orlandol23.payout.api.correlation.CorrelationId;
import io.github.orlandol23.payout.api.payout.web.PayoutController;
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
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End to end against a real PostgreSQL and a real broker: HTTP in, migrated
 * schema and a published event out.
 *
 * <p>Testcontainers rather than H2. The whole idempotency design rests on a
 * partial unique index and on PostgreSQL's behaviour when two transactions race
 * into it, and H2 does not reproduce either faithfully. A test that passes on a
 * different database than production is a test that lies.
 *
 * <p>Kafka is embedded rather than containerised, because there is nothing about
 * a broker in a container this needs that an in-JVM KRaft broker does not give.
 * The database is the part that has to be the real thing.
 *
 * <p>Named {@code *IT} so Failsafe runs it in the integration-test phase and
 * {@code mvn test} stays fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = PayoutTopics.PAYOUT_REQUESTED)
@EnabledIf(value = "dockerIsAvailable", disabledReason = "Docker is required to start the PostgreSQL container")
class PayoutApiIT {

    /**
     * Same major version as docker-compose.yml and as the target RDS instance.
     * Testing on a different major than production is how a migration passes CI
     * and fails on deploy.
     *
     * <p>Static, so one container serves the whole class instead of paying the
     * startup cost per test. {@code @ServiceConnection} points the application's
     * DataSource at it with no {@code @DynamicPropertySource} plumbing.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerIsAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private Consumer<String, PayoutRequested> consumer;

    /**
     * The container is shared, and these tests talk real HTTP, so there is no
     * transaction to roll back. Truncating is the isolation.
     *
     * <p>The topic is shared for the same reason, so the consumer is seeked to
     * the end of it: each test sees only the events its own requests produced.
     */
    @BeforeEach
    void clearPayoutsAndSubscribe() {
        jdbcTemplate.execute("TRUNCATE TABLE payouts");

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "payout-api-it-" + UUID.randomUUID());
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

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Flyway applied the migration and Hibernate validated the entity against it")
    void schemaIsMigratedAndValidated() {
        // The context started at all, which means ddl-auto=validate agreed that
        // every mapped column exists with a compatible type.
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied).contains("V1__create_payouts_table.sql");
    }

    @Test
    @DisplayName("the idempotency index is unique and partial, so keyless payouts are not indexed")
    void idempotencyIndexIsPartial() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_payouts_idempotency_key'", String.class);

        assertThat(definition)
                .contains("CREATE UNIQUE INDEX")
                .contains("WHERE (idempotency_key IS NOT NULL)");
    }

    @Test
    @DisplayName("the database refuses a status the application enum does not know")
    void checkConstraintRejectsUnknownStatus() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO payouts (id, amount, currency, status, correlation_id, attempts, created_at, updated_at)
                VALUES (?, 10.0000, 'USD', 'SOMETHING_ELSE', 'corr', 0, now(), now())
                """, id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the database refuses a non positive amount even when the API is bypassed")
    void checkConstraintRejectsNonPositiveAmount() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO payouts (id, amount, currency, status, correlation_id, attempts, created_at, updated_at)
                VALUES (?, 0, 'USD', 'PENDING', 'corr', 0, now(), now())
                """, id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a created payout can be read back with its amount scale intact")
    void createThenRead() {
        ResponseEntity<JsonNode> created = post("""
                {"amount": 1234.5, "currency": "BRL"}
                """, null, null);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().get("id").asText();
        assertThat(created.getHeaders().getLocation()).hasToString("/payouts/" + id);
        assertThat(created.getBody().get("status").asText()).isEqualTo("PENDING");

        ResponseEntity<JsonNode> read = restTemplate.getForEntity("/payouts/" + id, JsonNode.class);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().get("id").asText()).isEqualTo(id);
        // numeric(19,4) survived the round trip without turning into a double.
        assertThat(read.getBody().get("amount").decimalValue()).isEqualByComparingTo("1234.5000");
        assertThat(read.getBody().get("currency").asText()).isEqualTo("BRL");
        assertThat(read.getBody().get("attempts").asInt()).isZero();
        assertThat(read.getBody().get("lastError").isNull()).isTrue();
    }

    @Test
    @DisplayName("the correlation id from the request is what gets persisted on the row")
    void persistsCorrelationIdFromTheRequest() {
        ResponseEntity<JsonNode> created = post("""
                {"amount": 10.00, "currency": "USD"}
                """, null, "trace-abc-123");

        String id = created.getBody().get("id").asText();
        String stored = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM payouts WHERE id = ?::uuid", String.class, id);

        assertThat(stored).isEqualTo("trace-abc-123");
        assertThat(created.getBody().get("correlationId").asText()).isEqualTo("trace-abc-123");
    }

    @Test
    @DisplayName("an unknown id is a 404 problem detail")
    void unknownIdIsNotFound() {
        ResponseEntity<JsonNode> response =
                restTemplate.getForEntity("/payouts/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("type").asText()).isEqualTo("urn:payout:error:payout-not-found");
        assertThat(response.getBody().get("correlationId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("an invalid body is rejected before anything is written")
    void invalidBodyWritesNothing() {
        ResponseEntity<JsonNode> response = post("""
                {"amount": -5, "currency": "XYZ"}
                """, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors")).hasSize(2);
        assertThat(countPayouts()).isZero();
    }

    // ------------------------------------------------------------------
    // Idempotency, which is the point of the whole design
    // ------------------------------------------------------------------

    @Test
    @DisplayName("replaying the same idempotency key returns the first payout and creates no second row")
    void sameKeyReplaysInsteadOfDuplicating() {
        String key = "order-" + UUID.randomUUID();
        String body = """
                {"amount": 250.00, "currency": "BRL"}
                """;

        ResponseEntity<JsonNode> first = post(body, key, null);
        ResponseEntity<JsonNode> second = post(body, key, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 200 rather than 201: the retry succeeded, but it did not create anything.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());
        assertThat(countPayouts()).isEqualTo(1);
    }

    @Test
    @DisplayName("different keys create different payouts")
    void differentKeysCreateDifferentPayouts() {
        String body = """
                {"amount": 250.00, "currency": "BRL"}
                """;

        ResponseEntity<JsonNode> first = post(body, "key-a", null);
        ResponseEntity<JsonNode> second = post(body, "key-b", null);

        assertThat(first.getBody().get("id").asText()).isNotEqualTo(second.getBody().get("id").asText());
        assertThat(countPayouts()).isEqualTo(2);
    }

    @Test
    @DisplayName("payouts without a key are never deduplicated, because the index skips them")
    void keylessPayoutsAreNotDeduplicated() {
        String body = """
                {"amount": 250.00, "currency": "BRL"}
                """;

        post(body, null, null);
        post(body, null, null);

        assertThat(countPayouts()).isEqualTo(2);
    }

    /**
     * The test the whole design exists for.
     *
     * <p>Concurrent identical requests, released together, all reach the insert
     * at once. Exactly one must win and every caller must be handed the winner's
     * payout. A read-then-write check in application code fails this; the unique
     * index plus the recovery path passes it.
     */
    @Test
    @DisplayName("concurrent requests with one key produce exactly one payout")
    void concurrentRequestsWithOneKeyProduceOnePayout() throws Exception {
        int callers = 12;
        String key = "burst-" + UUID.randomUUID();
        String body = """
                {"amount": 77.25, "currency": "USD"}
                """;

        CountDownLatch release = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    release.await(10, TimeUnit.SECONDS);
                    return post(body, key, null);
                }));
            }
            release.countDown();

            Set<String> ids = new HashSet<>();
            int createdCount = 0;
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                ResponseEntity<JsonNode> response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode())
                        .as("every caller gets a success, whether it won the race or not")
                        .isIn(HttpStatus.CREATED, HttpStatus.OK);
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    createdCount++;
                }
                ids.add(response.getBody().get("id").asText());
            }

            assertThat(ids).as("all callers received the same payout").hasSize(1);
            assertThat(createdCount).as("exactly one caller actually created it").isEqualTo(1);
        }

        assertThat(countPayouts()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Kafka: what the worker actually receives
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a created payout is published on payout.requested, keyed by its id")
    void createPublishesTheEvent() {
        ResponseEntity<JsonNode> created = post("""
                {"amount": 125.50, "currency": "BRL"}
                """, null, "trace-abc-123");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().get("id").asText();

        ConsumerRecord<String, PayoutRequested> record =
                KafkaTestUtils.getSingleRecord(consumer, PayoutTopics.PAYOUT_REQUESTED, Duration.ofSeconds(20));

        assertThat(record.key()).isEqualTo(id);
        assertThat(record.value().payoutId()).hasToString(id);
        assertThat(record.value().amount()).isEqualByComparingTo("125.5000");
        assertThat(record.value().currency()).isEqualTo("BRL");
        assertThat(record.value().correlationId()).isEqualTo("trace-abc-123");

        Header correlationHeader = record.headers().lastHeader(CorrelationId.HEADER);
        assertThat(correlationHeader).isNotNull();
        assertThat(new String(correlationHeader.value(), StandardCharsets.UTF_8)).isEqualTo("trace-abc-123");
    }

    @Test
    @DisplayName("a replayed request publishes nothing, so the worker is not handed the same payout twice")
    void replayPublishesNothing() {
        String key = "order-" + UUID.randomUUID();
        String body = """
                {"amount": 250.00, "currency": "BRL"}
                """;

        post(body, key, null);
        // Drain the event the first request legitimately produced.
        KafkaTestUtils.getSingleRecord(consumer, PayoutTopics.PAYOUT_REQUESTED, Duration.ofSeconds(20));

        ResponseEntity<JsonNode> replay = post(body, key, null);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(3)).isEmpty())
                .as("nothing else reached the topic")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResponseEntity<JsonNode> post(String body, String idempotencyKey, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set(PayoutController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        if (correlationId != null) {
            headers.set(CorrelationId.HEADER, correlationId);
        }
        return restTemplate.exchange("/payouts", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private int countPayouts() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payouts", Integer.class);
    }
}
