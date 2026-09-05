package io.github.orlandol23.payout.api.payout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single payout request and everything the pipeline needs to know about it.
 *
 * <p>This table doubles as the work queue. The worker claims rows with a
 * conditional {@code UPDATE ... WHERE status = 'PENDING'} rather than holding a
 * lock, so two workers consuming the same Kafka event cannot both win.
 *
 * <p>There is deliberately no {@code @Version} column. Optimistic locking would
 * detect a concurrent write and throw, which is the wrong shape here: the claim
 * needs to be a silent no-op for the loser, not an exception. The conditional
 * UPDATE gives that for free.
 */
@Entity
@Table(name = "payouts")
public class Payout {

    /** Longest idempotency key we accept, mirrored by the column width. */
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    /** Decimal places stored for every amount, mirrored by {@code numeric(19,4)}. */
    public static final int AMOUNT_SCALE = 4;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Caller supplied key that makes {@code POST /payouts} safe to retry.
     * Optional: a caller that does not send one gets at-least-once creation
     * semantics and accepts the risk. Enforced by a partial unique index.
     */
    @Column(name = "idempotency_key", length = IDEMPOTENCY_KEY_MAX_LENGTH, updatable = false)
    private String idempotencyKey;

    /**
     * Hash of the request this row was created from, so a replayed idempotency
     * key can be told apart from a reused one.
     *
     * <p>Written once, never updated: the fingerprint describes the request that
     * created the payout, and a payout whose request changed is a different
     * payout. Null only on rows created before V2, which is why the service
     * treats null as "unknown" and lets the replay through.
     */
    @Column(name = "idempotency_fingerprint", length = IdempotencyFingerprint.LENGTH, updatable = false)
    private String idempotencyFingerprint;

    @Column(name = "amount", nullable = false, precision = 19, scale = AMOUNT_SCALE)
    private BigDecimal amount;

    /** ISO 4217 alphabetic code, always upper case. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PayoutStatus status;

    /** Ties this row to the request that created it and to every log line about it. */
    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false)
    private String correlationId;

    /** How many times a worker has tried to settle this payout. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Last failure reason, truncated to fit. Null until something fails. */
    @Column(name = "last_error", length = 2048)
    private String lastError;

    /**
     * When a retried payout becomes claimable again. Null means "now".
     *
     * <p>Mapped read only, and that is the whole point of the annotations. The
     * worker owns this column and writes it through its claim statement, never
     * through this entity; mapping it here only lets {@code GET /payouts/{id}}
     * tell a caller when the next attempt is due. {@code insertable = false}
     * keeps the API from writing a value it has no business deciding, and
     * {@code updatable = false} keeps a stale entity from overwriting one the
     * worker just set.
     *
     * <p>{@code locked_at} is deliberately <em>not</em> mapped. Hibernate's
     * {@code validate} asserts that every mapped column exists, not that every
     * column is mapped, so a column only the worker cares about can simply not
     * be here.
     */
    @Column(name = "next_attempt_at", insertable = false, updatable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not part of the public API. */
    protected Payout() {
    }

    private Payout(UUID id,
                   String idempotencyKey,
                   BigDecimal amount,
                   String currency,
                   String correlationId,
                   Instant now) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.correlationId = correlationId;
        this.status = PayoutStatus.PENDING;
        this.attempts = 0;
        this.lastError = null;
        this.createdAt = now;
        this.updatedAt = now;
        // Derived here rather than passed in, so there is one definition of what
        // "the same request" means and no caller can hand us a fingerprint that
        // does not match the row it is stored on.
        this.idempotencyFingerprint = IdempotencyFingerprint.of(amount, currency);
    }

    /**
     * Builds a payout in its only valid initial state.
     *
     * <p>A static factory rather than a public constructor, so there is exactly
     * one way to create a payout and it always starts {@code PENDING} with zero
     * attempts. The amount is normalised to the stored scale here so that
     * {@code 10.5} and {@code 10.5000} are the same value in every response.
     *
     * @param now injected instead of read from the system clock, so tests can
     *            assert exact timestamps
     */
    public static Payout request(UUID id,
                                 String idempotencyKey,
                                 BigDecimal amount,
                                 String currency,
                                 String correlationId,
                                 Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(now, "now");
        return new Payout(
                id,
                idempotencyKey,
                amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY),
                currency,
                correlationId,
                now);
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /** Null on rows created before V2: unknown, not "no fingerprint matches". */
    public String getIdempotencyFingerprint() {
        return idempotencyFingerprint;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PayoutStatus getStatus() {
        return status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    /** When the worker will try again. Null while the payout is claimable now. */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Identity is the assigned UUID, which exists before the row does.
     *
     * <p>Deliberately not generated from the mutable business fields: an entity
     * must not change its hash code when its status moves on, or it gets lost
     * inside a {@code HashSet} held across a transaction.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Payout payout)) {
            return false;
        }
        return id != null && id.equals(payout.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Payout[id=%s, status=%s, amount=%s %s, attempts=%d]"
                .formatted(id, status, amount, currency, attempts);
    }
}
