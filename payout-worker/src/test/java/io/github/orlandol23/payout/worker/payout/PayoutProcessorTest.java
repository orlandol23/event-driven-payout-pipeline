package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.worker.config.WorkerProperties;
import io.github.orlandol23.payout.worker.settlement.ErrorClassifier;
import io.github.orlandol23.payout.worker.settlement.SettlementGateway;
import io.github.orlandol23.payout.worker.settlement.SettlementInstruction;
import io.github.orlandol23.payout.worker.settlement.SettlementRejectedException;
import io.github.orlandol23.payout.worker.settlement.SettlementUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every outcome a payout can have, decided in isolation.
 *
 * <p>The repository and the gateway are mocks because these are decisions, and
 * the only way to reproduce "the provider timed out on the last attempt"
 * deterministically is to say so. The classifier and the backoff are real: they
 * are pure, they have their own tests, and substituting them here would leave
 * the interesting question, which delay actually reaches the row, unanswered.
 *
 * <p>The clock is fixed, so {@code next_attempt_at} is asserted as one exact
 * instant rather than as a window.
 */
@ExtendWith(MockitoExtension.class)
class PayoutProcessorTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Instant REQUESTED_AT = Instant.parse("2026-07-27T10:00:00Z");
    private static final String CORRELATION_ID = "corr-abc-123";
    private static final int MAX_ATTEMPTS = 3;

    private static final UUID PAYOUT_ID = UUID.randomUUID();

    @Mock
    private PayoutClaimRepository repository;

    @Mock
    private SettlementGateway settlementGateway;

    @Mock
    private PayoutDeadLetterPublisher deadLetterPublisher;

    @Captor
    private ArgumentCaptor<PayoutRequested> eventCaptor;

    @Captor
    private ArgumentCaptor<String> lastErrorCaptor;

    private PayoutProcessor processor;

    private PayoutProcessor processor() {
        if (processor == null) {
            processor = new PayoutProcessor(
                    repository,
                    settlementGateway,
                    new ErrorClassifier(),
                    new BackoffSchedule(),
                    deadLetterPublisher,
                    new WorkerProperties(MAX_ATTEMPTS, Duration.ofMinutes(5), Duration.ofSeconds(15), false, 50),
                    FIXED_CLOCK);
        }
        return processor;
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("a payout it could not claim is not settled, and that is the whole idempotency story")
        void losingTheClaimSettlesNothing() {
            // Empty is what a redelivery, a duplicate, a payout another worker
            // holds and a payout the scan already took all look like from here.
            when(repository.claim(eq(PAYOUT_ID), any())).thenReturn(Optional.empty());

            boolean worked = processor().claimAndProcess(PAYOUT_ID);

            assertThat(worked).isFalse();
            verifyNoInteractions(settlementGateway, deadLetterPublisher);
            verify(repository, never()).confirm(any(), any());
            verify(repository, never()).scheduleRetry(any(), any(), any(), any());
            verify(repository, never()).fail(any(), any(), any());
        }

        @Test
        @DisplayName("the claim is stamped with the injected clock, not the wall clock")
        void claimsWithTheInjectedClock() {
            when(repository.claim(eq(PAYOUT_ID), any())).thenReturn(Optional.of(claimed(1)));

            processor().claimAndProcess(PAYOUT_ID);

            verify(repository).claim(PAYOUT_ID, NOW);
        }

        @Test
        @DisplayName("a claimed payout is settled and confirmed")
        void aClaimedPayoutIsSettled() {
            when(repository.claim(eq(PAYOUT_ID), any())).thenReturn(Optional.of(claimed(1)));

            boolean worked = processor().claimAndProcess(PAYOUT_ID);

            assertThat(worked).isTrue();
            verify(settlementGateway).settle(new SettlementInstruction(
                    PAYOUT_ID, new BigDecimal("125.5000"), "BRL", CORRELATION_ID));
            verify(repository).confirm(PAYOUT_ID, NOW);
            verifyNoInteractions(deadLetterPublisher);
        }
    }

    @Nested
    @DisplayName("transient failures")
    class TransientFailures {

        @Test
        @DisplayName("are scheduled for the exact instant the backoff ladder says")
        void areRetriedOnTheLadder() {
            doThrow(new SettlementUnavailableException("provider is down"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(1));

            // First attempt failed, so one minute from the fixed clock.
            verify(repository).scheduleRetry(eq(PAYOUT_ID), eq(Instant.parse("2026-07-27T10:16:30Z")),
                    any(), eq(NOW));
            verify(repository, never()).fail(any(), any(), any());
            verifyNoInteractions(deadLetterPublisher);
        }

        @Test
        @DisplayName("move up the ladder as the attempts add up")
        void theSecondRetryWaitsLonger() {
            doThrow(new SettlementUnavailableException("provider is down"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(2));

            verify(repository).scheduleRetry(eq(PAYOUT_ID), eq(Instant.parse("2026-07-27T10:20:30Z")),
                    any(), eq(NOW));
        }

        @Test
        @DisplayName("record the exception type as well as its message, because 'connection reset' names no layer")
        void recordTheFailureReason() {
            doThrow(new SettlementUnavailableException("provider is down"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(1));

            verify(repository).scheduleRetry(any(), any(), lastErrorCaptor.capture(), any());
            assertThat(lastErrorCaptor.getValue())
                    .isEqualTo("SettlementUnavailableException: provider is down");
        }

        @Test
        @DisplayName("on the last attempt are failed and dead lettered as exhausted, not retried again")
        void theLastAttemptExhaustsTheBudget() {
            doThrow(new SettlementUnavailableException("provider is still down"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(MAX_ATTEMPTS));

            verify(repository, never()).scheduleRetry(any(), any(), any(), any());
            verify(repository).fail(eq(PAYOUT_ID), lastErrorCaptor.capture(), eq(NOW));
            assertThat(lastErrorCaptor.getValue()).contains("provider is still down");
            verify(deadLetterPublisher).publish(any(), eq(DeadLetterReason.EXHAUSTED), any(), eq(MAX_ATTEMPTS));
        }

        @Test
        @DisplayName("an exception nobody anticipated is treated as transient")
        void unexpectedFailuresAreRetried() {
            // The safe direction, and the reason the attempt budget exists: a
            // failure of unknown permanence costs a few retries rather than a
            // payout the caller was already told we accepted.
            doThrow(new IllegalStateException("connection pool exhausted"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(1));

            verify(repository).scheduleRetry(eq(PAYOUT_ID), any(), any(), eq(NOW));
            verify(repository, never()).fail(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("permanent failures")
    class PermanentFailures {

        @Test
        @DisplayName("are failed and dead lettered on the first attempt, with no retry")
        void areNeverRetried() {
            doThrow(new SettlementRejectedException("account closed"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(1));

            verify(repository, never()).scheduleRetry(any(), any(), any(), any());
            verify(repository).fail(eq(PAYOUT_ID), lastErrorCaptor.capture(), eq(NOW));
            assertThat(lastErrorCaptor.getValue()).isEqualTo("SettlementRejectedException: account closed");
            verify(deadLetterPublisher).publish(any(), eq(DeadLetterReason.PERMANENT), any(), eq(1));
        }

        @Test
        @DisplayName("carry the original event to the dead letter topic, rebuilt from the row")
        void theDeadLetterCarriesTheOriginalEvent() {
            doThrow(new SettlementRejectedException("account closed"))
                    .when(settlementGateway).settle(any());

            processor().process(claimed(1));

            verify(deadLetterPublisher).publish(eventCaptor.capture(), any(), any(), anyInt());
            // requestedAt is the moment the API accepted the request, not the
            // moment it failed. Rebuilt from the row, so a payout the claim scan
            // found dead letters exactly like one that arrived over Kafka.
            assertThat(eventCaptor.getValue()).isEqualTo(new PayoutRequested(
                    PAYOUT_ID, new BigDecimal("125.5000"), "BRL", CORRELATION_ID, REQUESTED_AT));
        }
    }

    @Nested
    @DisplayName("a stale lock reclaimed past the budget")
    class ReclaimedPastTheBudget {

        @Test
        @DisplayName("is failed without settling again, because that attempt is one too many")
        void isNotSettledAgain() {
            // Only reachable when a worker died after the final attempt was
            // counted and before it could record the outcome. Settling here
            // would be an attempt past the limit, which for money is the one
            // thing the limit exists to prevent.
            processor().process(claimed(MAX_ATTEMPTS + 1));

            verifyNoInteractions(settlementGateway);
            verify(repository).fail(eq(PAYOUT_ID), any(), eq(NOW));
            verify(deadLetterPublisher).publish(any(), eq(DeadLetterReason.EXHAUSTED), any(),
                    eq(MAX_ATTEMPTS + 1));
        }
    }

    private static ClaimedPayout claimed(int attempts) {
        return new ClaimedPayout(
                PAYOUT_ID, new BigDecimal("125.5000"), "BRL", CORRELATION_ID, attempts, REQUESTED_AT);
    }
}
