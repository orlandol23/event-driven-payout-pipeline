package io.github.orlandol23.payout.worker.payout;

import io.github.orlandol23.payout.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The claim scan's own behaviour: what it claims, in what order, and what it
 * does when something goes wrong halfway through a batch.
 */
@ExtendWith(MockitoExtension.class)
class PayoutPollerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int BATCH_SIZE = 25;

    @Mock
    private PayoutClaimRepository repository;

    @Mock
    private PayoutProcessor processor;

    private PayoutPoller poller;

    @BeforeEach
    void createPoller() {
        poller = new PayoutPoller(
                repository,
                processor,
                new WorkerProperties(5, Duration.ofMinutes(5), Duration.ofSeconds(15), true, BATCH_SIZE),
                FIXED_CLOCK);
    }

    @Test
    @DisplayName("claims a bounded batch, stamped with the injected clock")
    void claimsABoundedBatch() {
        when(repository.claimDue(BATCH_SIZE, NOW)).thenReturn(List.of());

        poller.claimDuePayouts();

        verify(repository).claimDue(BATCH_SIZE, NOW);
        verifyNoInteractions(processor);
    }

    @Test
    @DisplayName("settles every row it claimed, in the order the scan returned them")
    void settlesTheWholeBatchInOrder() {
        ClaimedPayout first = claimed();
        ClaimedPayout second = claimed();
        when(repository.claimDue(BATCH_SIZE, NOW)).thenReturn(List.of(first, second));

        poller.claimDuePayouts();

        // Oldest first, and sequentially: the batch is small, settlement is IO
        // bound, and the worker scales by adding instances rather than threads.
        var order = inOrder(processor);
        order.verify(processor).process(first);
        order.verify(processor).process(second);
    }

    @Test
    @DisplayName("one payout blowing up does not abandon the rest of the batch")
    void oneFailureDoesNotAbandonTheBatch() {
        ClaimedPayout poison = claimed();
        ClaimedPayout healthy = claimed();
        when(repository.claimDue(BATCH_SIZE, NOW)).thenReturn(List.of(poison, healthy));
        doThrow(new IllegalStateException("boom")).when(processor).process(poison);

        assertThatCode(poller::claimDuePayouts).doesNotThrowAnyException();

        // The failed row keeps its lock and is reclaimed after the stale
        // timeout. Leaving the rest of the batch locked and unprocessed would
        // mean every later scan re-claims rows it never tried to settle.
        verify(processor).process(healthy);
    }

    @Test
    @DisplayName("a database that cannot be reached is logged, not thrown, so the schedule survives")
    void aFailedScanDoesNotKillTheSchedule() {
        when(repository.claimDue(BATCH_SIZE, NOW))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        // Nothing is lost by failing here: every row the scan would have claimed
        // is still exactly where it was. An exception escaping a @Scheduled
        // method is a poller that stops for good.
        assertThatCode(poller::claimDuePayouts).doesNotThrowAnyException();
        verify(processor, org.mockito.Mockito.never()).process(any());
    }

    private static ClaimedPayout claimed() {
        return new ClaimedPayout(
                UUID.randomUUID(),
                new BigDecimal("125.5000"),
                "BRL",
                "corr-abc-123",
                1,
                Instant.parse("2026-07-27T10:00:00Z"));
    }
}
