package io.github.orlandol23.payout.worker.payout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deliberate truncation of {@code last_error}.
 *
 * <p>Small, and worth its own test because it is the one place the worker
 * knowingly loses information. {@code last_error} exists to say what went wrong,
 * not to archive a stack trace: the trace goes to the log, under the correlation
 * id, where it can be read in full and expires on the log's retention rather
 * than growing the payouts table forever.
 */
class PayoutClaimRepositoryTest {

    @Test
    @DisplayName("a message that fits is left exactly as it is")
    void shortMessagesAreUntouched() {
        assertThat(PayoutClaimRepository.truncateLastError("SettlementRejectedException: account closed"))
                .isEqualTo("SettlementRejectedException: account closed");
    }

    @Test
    @DisplayName("null stays null, because 'nothing failed' is not an empty string")
    void nullStaysNull() {
        assertThat(PayoutClaimRepository.truncateLastError(null)).isNull();
    }

    @Test
    @DisplayName("a message longer than the column is cut to the column, not rejected")
    void longMessagesAreCutToTheColumn() {
        String tooLong = "x".repeat(PayoutClaimRepository.LAST_ERROR_MAX_LENGTH + 500);

        String truncated = PayoutClaimRepository.truncateLastError(tooLong);

        assertThat(truncated).hasSize(PayoutClaimRepository.LAST_ERROR_MAX_LENGTH);
        assertThat(tooLong).startsWith(truncated);
    }

    @Test
    @DisplayName("a message exactly the width of the column is not cut")
    void theBoundaryIsInclusive() {
        String exact = "y".repeat(PayoutClaimRepository.LAST_ERROR_MAX_LENGTH);

        assertThat(PayoutClaimRepository.truncateLastError(exact)).isEqualTo(exact);
    }
}
