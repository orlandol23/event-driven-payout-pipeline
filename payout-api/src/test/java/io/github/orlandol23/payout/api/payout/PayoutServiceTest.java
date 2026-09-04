package io.github.orlandol23.payout.api.payout;

import io.github.orlandol23.payout.api.payout.events.PayoutEventPublisher;
import io.github.orlandol23.payout.contracts.PayoutRequested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.KafkaException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the creation rules and the idempotency race recovery.
 *
 * <p>The repository is a mock here on purpose: these tests are about the
 * decisions the service makes, and the only way to reproduce "another request
 * won the insert race" deterministically is to make the repository behave as if
 * one had. Whether the unique index actually fires is a database question, so it
 * is answered by {@code PayoutApiIT} against a real PostgreSQL instead.
 */
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String CORRELATION_ID = "corr-abc-123";
    private static final String IDEMPOTENCY_KEY = "key-abc-123";

    @Mock
    private PayoutRepository repository;

    @Mock
    private PayoutEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<Payout> payoutCaptor;

    @Captor
    private ArgumentCaptor<PayoutRequested> eventCaptor;

    private PayoutService service;

    private PayoutService service() {
        if (service == null) {
            service = new PayoutService(repository, eventPublisher, FIXED_CLOCK);
        }
        return service;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("stores a new payout as PENDING with zero attempts")
        void storesNewPayoutAsPending() {
            when(repository.saveAndFlush(any(Payout.class))).thenAnswer(call -> call.getArgument(0));

            PayoutCreation creation = service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            verify(repository).saveAndFlush(payoutCaptor.capture());
            Payout saved = payoutCaptor.getValue();

            assertThat(creation.replayed()).isFalse();
            assertThat(saved.getStatus()).isEqualTo(PayoutStatus.PENDING);
            assertThat(saved.getAttempts()).isZero();
            assertThat(saved.getLastError()).isNull();
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCurrency()).isEqualTo("BRL");
            assertThat(saved.getCorrelationId()).isEqualTo(CORRELATION_ID);
            assertThat(saved.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        }

        @Test
        @DisplayName("normalises the amount to the stored scale so 10.5 and 10.5000 are one value")
        void normalisesAmountScale() {
            when(repository.saveAndFlush(any(Payout.class))).thenAnswer(call -> call.getArgument(0));

            service().create(command(new BigDecimal("10.5"), "USD", null));

            verify(repository).saveAndFlush(payoutCaptor.capture());
            assertThat(payoutCaptor.getValue().getAmount()).isEqualByComparingTo("10.5");
            assertThat(payoutCaptor.getValue().getAmount().scale()).isEqualTo(Payout.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("timestamps come from the injected clock, not from the wall clock")
        void timestampsComeFromTheInjectedClock() {
            when(repository.saveAndFlush(any(Payout.class))).thenAnswer(call -> call.getArgument(0));

            service().create(command(new BigDecimal("1.0000"), "EUR", null));

            verify(repository).saveAndFlush(payoutCaptor.capture());
            assertThat(payoutCaptor.getValue().getCreatedAt()).isEqualTo(NOW);
            assertThat(payoutCaptor.getValue().getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("skips the idempotency lookup entirely when no key was supplied")
        void skipsLookupWithoutKey() {
            when(repository.saveAndFlush(any(Payout.class))).thenAnswer(call -> call.getArgument(0));

            service().create(command(new BigDecimal("5.0000"), "USD", null));

            verify(repository, never()).findByIdempotencyKey(any());
        }

        @Test
        @DisplayName("returns the existing payout, and does not insert, when the key was already used")
        void replaysExistingPayout() {
            Payout existing = existingPayout();
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            PayoutCreation creation = service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            assertThat(creation.replayed()).isTrue();
            assertThat(creation.payout()).isSameAs(existing);
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("recovers by re-reading when a concurrent request won the insert race")
        void recoversAfterLosingTheInsertRace() {
            Payout winner = existingPayout();
            // Nothing on the first read: the competing transaction has not
            // committed yet. It commits before our insert reaches the index.
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(repository.saveAndFlush(any(Payout.class)))
                    .thenThrow(new DataIntegrityViolationException("ux_payouts_idempotency_key"));

            PayoutCreation creation = service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            assertThat(creation.replayed()).isTrue();
            assertThat(creation.payout()).isSameAs(winner);
        }

        @Test
        @DisplayName("rethrows when the violation was not the idempotency index")
        void rethrowsUnrelatedViolation() {
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any(Payout.class)))
                    .thenThrow(new DataIntegrityViolationException("ck_payouts_amount_positive"));

            assertThatThrownBy(() -> service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("rethrows a violation when there is no key to recover with")
        void rethrowsWhenThereIsNoKey() {
            when(repository.saveAndFlush(any(Payout.class)))
                    .thenThrow(new DataIntegrityViolationException("some other constraint"));

            assertThatThrownBy(() -> service().create(command(new BigDecimal("125.50"), "BRL", null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verify(repository, never()).findByIdempotencyKey(any());
        }
    }

    /**
     * The promise an idempotency key makes, and what happens when a caller
     * breaks it.
     *
     * <p>A key says "this is the same request again". The fingerprint is what
     * lets the service check rather than assume, and the three cases below are
     * the only three there are: same request, different request, and a row from
     * before the column existed.
     */
    @Nested
    @DisplayName("idempotency key reuse")
    class KeyReuse {

        @Test
        @DisplayName("the same key with the same body is a replay, as it always was")
        void sameBodyStillReplays() {
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingPayout()));

            PayoutCreation creation = service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            assertThat(creation.replayed()).isTrue();
        }

        @Test
        @DisplayName("trailing zeros are not a different request")
        void scaleDoesNotMakeItADifferentRequest() {
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingPayout()));

            PayoutCreation creation = service().create(command(new BigDecimal("125.5"), "BRL", IDEMPOTENCY_KEY));

            assertThat(creation.replayed()).isTrue();
        }

        @Test
        @DisplayName("the same key with a different amount is rejected, not answered with the first payout")
        void differentAmountIsRejected() {
            Payout existing = existingPayout();
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service().create(command(new BigDecimal("999.99"), "BRL", IDEMPOTENCY_KEY)))
                    .isInstanceOf(IdempotencyKeyReusedException.class)
                    .extracting(failure -> ((IdempotencyKeyReusedException) failure).getExistingPayoutId())
                    .isEqualTo(existing.getId());
            verify(repository, never()).saveAndFlush(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("the same key with a different currency is rejected too")
        void differentCurrencyIsRejected() {
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingPayout()));

            assertThatThrownBy(() -> service().create(command(new BigDecimal("125.50"), "USD", IDEMPOTENCY_KEY)))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
        }

        @Test
        @DisplayName("a row created before V2 has no fingerprint, and unknown is not the same as different")
        void aRowWithoutAFingerprintStillReplays() {
            // Failing every replay of every pre-migration payout would be a
            // migration turning into an outage.
            Payout legacy = payoutWithoutFingerprint();
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(legacy));

            PayoutCreation creation = service().create(command(new BigDecimal("999.99"), "USD", IDEMPOTENCY_KEY));

            assertThat(creation.replayed()).isTrue();
            assertThat(creation.payout()).isSameAs(legacy);
        }

        @Test
        @DisplayName("losing the insert race to a different body is rejected, not silently replayed")
        void theRaceLoserIsCheckedToo() {
            // Two concurrent requests with one key and two bodies are the same
            // mistake as two sequential ones. The loser must not be the only
            // caller that gets away with it.
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingPayout()));
            when(repository.saveAndFlush(any(Payout.class)))
                    .thenThrow(new DataIntegrityViolationException("ux_payouts_idempotency_key"));

            assertThatThrownBy(() -> service().create(command(new BigDecimal("1.00"), "BRL", IDEMPOTENCY_KEY)))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
            verifyNoInteractions(eventPublisher);
        }
    }

    /**
     * Who gets told about a new payout, and what happens when telling them
     * fails.
     *
     * <p>Mocked rather than run against a broker on purpose: these are decisions
     * the service makes, and "the broker was unreachable" is not something an
     * embedded Kafka reproduces on demand. That the event reaches the topic
     * correctly is {@code PayoutEventPublisherTest}'s job.
     */
    @Nested
    @DisplayName("publishing payout.requested")
    class Publishing {

        @Test
        @DisplayName("publishes exactly once for a payout this call created")
        void publishesOnceForANewPayout() {
            when(repository.saveAndFlush(any(Payout.class))).thenAnswer(call -> call.getArgument(0));

            PayoutCreation creation = service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            verify(eventPublisher).publish(eventCaptor.capture());
            PayoutRequested event = eventCaptor.getValue();

            assertThat(event.payoutId()).isEqualTo(creation.payout().getId());
            assertThat(event.amount()).isEqualByComparingTo("125.5000");
            assertThat(event.currency()).isEqualTo("BRL");
            assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(event.requestedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("publishes nothing for a replay, because the first request already did")
        void publishesNothingForAReplay() {
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingPayout()));

            service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("publishes nothing after losing the insert race, for the same reason")
        void publishesNothingAfterLosingTheRace() {
            when(repository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(existingPayout()));
            when(repository.saveAndFlush(any(Payout.class)))
                    .thenThrow(new DataIntegrityViolationException("ux_payouts_idempotency_key"));

            service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            verifyNoInteractions(eventPublisher);
        }

        /**
         * The honest half of having no outbox. A broker that is down must not
         * turn a durable payout into a 500: the row is the queue, and the
         * worker's claim scan finds it whether the event was published or not.
         */
        @Test
        @DisplayName("a broker failure does not fail the request, because the row is already durable")
        void aPublishFailureDoesNotFailCreate() {
            when(repository.saveAndFlush(any(Payout.class))).thenAnswer(call -> call.getArgument(0));
            doThrow(new KafkaException("no broker available")).when(eventPublisher).publish(any());

            // No assertThatCode wrapper: if the failure escaped, this line throws
            // and the test fails, which is the assertion.
            PayoutCreation creation = service().create(command(new BigDecimal("125.50"), "BRL", IDEMPOTENCY_KEY));

            assertThat(creation.replayed()).as("still a 201, not a replay").isFalse();
            assertThat(creation.payout().getStatus()).isEqualTo(PayoutStatus.PENDING);
            verify(eventPublisher).publish(any());
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("returns the payout when it exists")
        void returnsPayout() {
            Payout existing = existingPayout();
            when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

            assertThat(service().findById(existing.getId())).isSameAs(existing);
        }

        @Test
        @DisplayName("throws PayoutNotFoundException carrying the id that was asked for")
        void throwsWhenMissing() {
            UUID missing = UUID.randomUUID();
            when(repository.findById(missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().findById(missing))
                    .isInstanceOf(PayoutNotFoundException.class)
                    .extracting(exception -> ((PayoutNotFoundException) exception).getPayoutId())
                    .isEqualTo(missing);
        }
    }

    private static CreatePayoutCommand command(BigDecimal amount, String currency, String idempotencyKey) {
        return new CreatePayoutCommand(amount, currency, idempotencyKey, CORRELATION_ID);
    }

    private static Payout existingPayout() {
        return Payout.request(UUID.randomUUID(), IDEMPOTENCY_KEY, new BigDecimal("125.5000"), "BRL",
                CORRELATION_ID, NOW);
    }

    /**
     * A payout as it comes back from a row written before V2 added the column.
     *
     * <p>The field is cleared reflectively rather than by adding a setter,
     * because nothing in production should be able to take a fingerprint off a
     * payout. This is the one state the entity cannot construct and the database
     * still contains.
     */
    private static Payout payoutWithoutFingerprint() {
        Payout legacy = existingPayout();
        ReflectionTestUtils.setField(legacy, "idempotencyFingerprint", null);
        return legacy;
    }
}
