package io.github.orlandol23.payout.worker;

import io.github.orlandol23.payout.contracts.PayoutTopics;
import io.github.orlandol23.payout.worker.payout.ClaimedPayout;
import io.github.orlandol23.payout.worker.payout.PayoutClaimRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim statements against a real PostgreSQL.
 *
 * <p>This is the day 3 twin of the API's concurrent idempotency test, and it
 * exists for the same reason. Everything else about the worker is provable in
 * memory; the claim is not. Whether twelve simultaneous claims produce exactly
 * one winner is a question about how PostgreSQL serialises conditional updates,
 * and whether the scan skips a row another transaction is holding is a question
 * about {@code FOR UPDATE SKIP LOCKED}. A map answers neither, and an in-memory
 * database that answers them differently from the one in production would be
 * worse than not asking.
 *
 * <p>The migrations are the API's own files, applied by Flyway to this
 * container. Copying the DDL here would let the two drift, and a claim tested
 * against a schema nobody deploys is a claim tested against nothing.
 *
 * <p>Named {@code *IT} so Failsafe runs it in the integration-test phase, and
 * skipped rather than failed where there is no Docker.
 */
@SpringBootTest(properties = {
        // Every test here drives the repository directly. A scan running in the
        // background would claim the rows out from under them.
        "payout.worker.poll-enabled=false",
        "payout.worker.stale-lock=5m"
})
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {PayoutTopics.PAYOUT_REQUESTED, PayoutTopics.PAYOUT_REQUESTED_DLT})
@EnabledIf(value = "dockerIsAvailable", disabledReason = "Docker is required to start the PostgreSQL container")
class PayoutWorkerIT {

    /** Same major version as docker-compose.yml, the API's tests and the target RDS instance. */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** The API's migrations, applied from source rather than copied. */
    private static final Path MIGRATIONS =
            Path.of("..", "payout-api", "src", "main", "resources", "db", "migration")
                    .toAbsolutePath()
                    .normalize();

    private static final Duration STALE_LOCK = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");

    static boolean dockerIsAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void migrate() {
        assertThat(MIGRATIONS).as("the API's migrations must be readable from the worker module")
                .isDirectory();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + MIGRATIONS)
                .load()
                .migrate();
    }

    @Autowired
    private PayoutClaimRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** No transaction to roll back: these statements are the thing under test. */
    @BeforeEach
    void clearPayouts() {
        jdbcTemplate.execute("TRUNCATE TABLE payouts");
    }

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the worker runs against the schema the API migrates, V2 included")
    void bothMigrationsApplied() {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied).contains("V1__create_payouts_table.sql", "V2__payouts_claim_columns.sql");
    }

    // ------------------------------------------------------------------
    // The claim
    // ------------------------------------------------------------------

    @Test
    @DisplayName("claiming a PENDING payout takes the lock, counts the attempt and returns the row")
    void claimingTakesTheRow() {
        UUID payoutId = pendingPayout(new BigDecimal("125.5000"), "BRL", NOW.minusSeconds(60));

        Optional<ClaimedPayout> claimed = repository.claim(payoutId, NOW);

        assertThat(claimed).isPresent();
        assertThat(claimed.get().amount()).isEqualByComparingTo("125.5000");
        assertThat(claimed.get().currency()).isEqualTo("BRL");
        assertThat(claimed.get().correlationId()).isEqualTo("corr-abc-123");
        assertThat(claimed.get().attempts()).as("counted at claim time, so the first attempt is 1").isEqualTo(1);
        assertThat(claimed.get().requestedAt())
                .as("the moment the API accepted the request, so the dead letter can rebuild the event")
                .isEqualTo(NOW.minusSeconds(60));

        assertThat(statusOf(payoutId)).isEqualTo("PROCESSING");
        assertThat(instantColumn(payoutId, "locked_at")).isEqualTo(NOW);
        assertThat(instantColumn(payoutId, "updated_at")).isEqualTo(NOW);
    }

    /**
     * The test this whole file exists for.
     *
     * <p>Twelve threads released together, all claiming one row. The conditional
     * UPDATE has to serialise them and let exactly one win; every loser updates
     * zero rows and gets nothing back. A read-then-write check fails this, and
     * for payouts each extra winner is a duplicate payment.
     */
    @Test
    @DisplayName("twelve concurrent claims of one payout produce exactly one winner")
    void exactlyOneClaimWins() throws Exception {
        int workers = 12;
        UUID payoutId = pendingPayout(new BigDecimal("77.2500"), "USD", NOW.minusSeconds(60));

        CountDownLatch release = new CountDownLatch(1);
        List<Future<Optional<ClaimedPayout>>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(pool.submit(() -> {
                    release.await(10, TimeUnit.SECONDS);
                    return repository.claim(payoutId, NOW);
                }));
            }
            release.countDown();

            long winners = 0;
            for (Future<Optional<ClaimedPayout>> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).as("exactly one worker holds the payout").isEqualTo(1);
        }

        assertThat(attemptsOf(payoutId))
                .as("eleven losers counted no attempt, because they updated no row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a redelivered event claims nothing, because the payout is already settled")
    void redeliveryIsANoOp() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));
        repository.claim(payoutId, NOW);
        repository.confirm(payoutId, NOW);

        Optional<ClaimedPayout> redelivered = repository.claim(payoutId, NOW.plusSeconds(1));

        assertThat(redelivered).isEmpty();
        assertThat(statusOf(payoutId)).isEqualTo("CONFIRMED");
        assertThat(attemptsOf(payoutId)).isEqualTo(1);
        assertThat(instantColumn(payoutId, "locked_at")).as("a settled payout holds no lock").isNull();
    }

    @Test
    @DisplayName("a payout whose retry is not due yet is not claimable")
    void aRetryThatIsNotDueIsNotClaimed() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));
        repository.claim(payoutId, NOW);
        repository.scheduleRetry(payoutId, NOW.plus(Duration.ofMinutes(5)), "provider is down", NOW);

        assertThat(repository.claim(payoutId, NOW.plus(Duration.ofMinutes(1)))).isEmpty();
        assertThat(repository.claim(payoutId, NOW.plus(Duration.ofMinutes(5)))).isPresent();
    }

    @Test
    @DisplayName("a lock older than the timeout is reclaimed; a fresh one is not")
    void staleLocksAreReclaimed() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));
        repository.claim(payoutId, NOW);

        // The worker that took it died holding it. Until the timeout the row is
        // untouchable, which is what keeps two workers off one payout.
        assertThat(repository.claim(payoutId, NOW.plus(STALE_LOCK.minusSeconds(1)))).isEmpty();

        Optional<ClaimedPayout> reclaimed = repository.claim(payoutId, NOW.plus(STALE_LOCK.plusSeconds(1)));

        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().attempts())
                .as("the dead worker's attempt is not refunded")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scheduling a retry returns the payout to the queue with its lock released")
    void scheduleRetryReleasesTheLock() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));
        repository.claim(payoutId, NOW);

        repository.scheduleRetry(payoutId, NOW.plus(Duration.ofMinutes(1)), "SettlementUnavailableException", NOW);

        assertThat(statusOf(payoutId)).isEqualTo("PENDING");
        assertThat(instantColumn(payoutId, "locked_at"))
                .as("a row waiting for its next attempt is not being worked on")
                .isNull();
        assertThat(instantColumn(payoutId, "next_attempt_at")).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(lastErrorOf(payoutId)).isEqualTo("SettlementUnavailableException");
    }

    @Test
    @DisplayName("last_error is truncated to the column rather than failing the write")
    void lastErrorIsTruncated() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));
        repository.claim(payoutId, NOW);

        repository.fail(payoutId, "e".repeat(PayoutClaimRepository.LAST_ERROR_MAX_LENGTH + 500), NOW);

        assertThat(lastErrorOf(payoutId)).hasSize(PayoutClaimRepository.LAST_ERROR_MAX_LENGTH);
        assertThat(statusOf(payoutId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a failed payout is terminal and never claimable again")
    void failIsTerminal() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));
        repository.claim(payoutId, NOW);
        repository.fail(payoutId, "SettlementRejectedException: account closed", NOW);

        assertThat(statusOf(payoutId)).isEqualTo("FAILED");
        assertThat(instantColumn(payoutId, "locked_at")).isNull();
        assertThat(repository.claim(payoutId, NOW.plus(Duration.ofDays(365)))).isEmpty();
    }

    @Test
    @DisplayName("a transition only applies to a row this worker is still holding")
    void transitionsRequireTheLock() {
        UUID payoutId = pendingPayout(new BigDecimal("10.0000"), "USD", NOW.minusSeconds(60));

        // Never claimed, so still PENDING. A confirm that ignored the status
        // would settle a payout nobody ever tried to settle.
        repository.confirm(payoutId, NOW);

        assertThat(statusOf(payoutId)).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------
    // The claim scan
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the scan claims due rows oldest first, up to the batch size")
    void claimDueTakesTheOldestFirst() {
        UUID oldest = pendingPayout(new BigDecimal("1.0000"), "USD", NOW.minus(Duration.ofMinutes(30)));
        UUID middle = pendingPayout(new BigDecimal("2.0000"), "USD", NOW.minus(Duration.ofMinutes(20)));
        pendingPayout(new BigDecimal("3.0000"), "USD", NOW.minus(Duration.ofMinutes(10)));

        List<ClaimedPayout> claimed = repository.claimDue(2, NOW);

        assertThat(claimed).extracting(ClaimedPayout::id).containsExactly(oldest, middle);
        assertThat(claimed).allMatch(payout -> payout.attempts() == 1);
    }

    @Test
    @DisplayName("the scan is what finds a payout whose payout.requested event was never published")
    void claimDueFindsRowsNoEventWasPublishedFor() {
        // Nothing here published anything: this is exactly the row the API
        // commits when the broker is down, and the reason Kafka is a latency
        // optimisation rather than the source of truth.
        UUID orphan = pendingPayout(new BigDecimal("42.0000"), "USD", NOW.minus(Duration.ofHours(1)));

        assertThat(repository.claimDue(10, NOW)).extracting(ClaimedPayout::id).containsExactly(orphan);
    }

    @Test
    @DisplayName("the scan skips a row another transaction is holding rather than blocking on it")
    void claimDueSkipsLockedRows() throws Exception {
        UUID held = pendingPayout(new BigDecimal("1.0000"), "USD", NOW.minus(Duration.ofMinutes(30)));
        UUID free = pendingPayout(new BigDecimal("2.0000"), "USD", NOW.minus(Duration.ofMinutes(20)));

        // A second connection holds a row lock, which is what another worker's
        // scan looks like from here. Without SKIP LOCKED this call would block
        // on it until the transaction below ends.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM payouts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, held);
                lock.executeQuery();
            }

            List<ClaimedPayout> claimed = repository.claimDue(10, NOW);

            assertThat(claimed)
                    .as("the locked row was skipped, and the scan still made progress on the rest")
                    .extracting(ClaimedPayout::id)
                    .containsExactly(free);

            holder.rollback();
        }

        assertThat(statusOf(held)).as("the skipped row is untouched and still claimable").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the scan ignores rows that are not due, terminal, or freshly locked")
    void claimDueIgnoresEverythingElse() {
        UUID confirmed = pendingPayout(new BigDecimal("1.0000"), "USD", NOW.minus(Duration.ofMinutes(40)));
        repository.claim(confirmed, NOW);
        repository.confirm(confirmed, NOW);

        UUID notDue = pendingPayout(new BigDecimal("2.0000"), "USD", NOW.minus(Duration.ofMinutes(30)));
        repository.claim(notDue, NOW);
        repository.scheduleRetry(notDue, NOW.plus(Duration.ofMinutes(30)), "provider is down", NOW);

        UUID freshlyLocked = pendingPayout(new BigDecimal("3.0000"), "USD", NOW.minus(Duration.ofMinutes(20)));
        repository.claim(freshlyLocked, NOW);

        UUID due = pendingPayout(new BigDecimal("4.0000"), "USD", NOW.minus(Duration.ofMinutes(10)));

        assertThat(repository.claimDue(10, NOW.plusSeconds(1)))
                .extracting(ClaimedPayout::id)
                .containsExactly(due);
    }

    @Test
    @DisplayName("the scan reclaims a stale lock, which is how a dead worker's payouts come back")
    void claimDueReclaimsStaleLocks() {
        UUID abandoned = pendingPayout(new BigDecimal("5.0000"), "USD", NOW.minus(Duration.ofMinutes(30)));
        repository.claim(abandoned, NOW);

        assertThat(repository.claimDue(10, NOW.plus(STALE_LOCK.minusSeconds(1)))).isEmpty();
        assertThat(repository.claimDue(10, NOW.plus(STALE_LOCK.plusSeconds(1))))
                .extracting(ClaimedPayout::id)
                .containsExactly(abandoned);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Stands in for {@code POST /payouts}, which is the only writer of new rows. */
    private UUID pendingPayout(BigDecimal amount, String currency, Instant createdAt) {
        UUID payoutId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payouts (id, idempotency_key, amount, currency, status, correlation_id,
                                     attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 'corr-abc-123', 0, ?, ?)
                """,
                payoutId,
                "key-" + payoutId,
                amount,
                currency,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return payoutId;
    }

    private String statusOf(UUID payoutId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payouts WHERE id = ?", String.class, payoutId);
    }

    private int attemptsOf(UUID payoutId) {
        return jdbcTemplate.queryForObject("SELECT attempts FROM payouts WHERE id = ?", Integer.class, payoutId);
    }

    private String lastErrorOf(UUID payoutId) {
        return jdbcTemplate.queryForObject("SELECT last_error FROM payouts WHERE id = ?", String.class, payoutId);
    }

    private Instant instantColumn(UUID payoutId, String column) {
        OffsetDateTime value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM payouts WHERE id = ?", OffsetDateTime.class, payoutId);
        return value == null ? null : value.toInstant();
    }
}
