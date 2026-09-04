package io.github.orlandol23.payout.worker.payout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry ladder, asserted as exact instants rather than as "about five
 * minutes".
 *
 * <p>This is what the injected {@link Clock} bought. With
 * {@code Instant.now()} inside the schedule, every assertion here would be a
 * range, and a test that asserts a range around a wall clock is a test that goes
 * red on a slow machine and green on a rerun.
 */
class BackoffScheduleTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final BackoffSchedule schedule = new BackoffSchedule();

    @Test
    @DisplayName("the ladder is one minute, then five, then thirty")
    void theLadder() {
        assertThat(schedule.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(schedule.delayAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(schedule.delayAfter(3)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("later attempts are clamped to thirty minutes rather than doubling away")
    void laterAttemptsAreClamped() {
        // Doubling past the end of the ladder would put the last attempts hours
        // apart, long after the transient condition either cleared or turned
        // out to be permanent.
        assertThat(schedule.delayAfter(4)).isEqualTo(Duration.ofMinutes(30));
        assertThat(schedule.delayAfter(50)).isEqualTo(Duration.ofMinutes(30));
        assertThat(schedule.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("next_attempt_at is the exact instant the clock says plus the delay")
    void nextAttemptIsAnExactInstant() {
        Instant now = Instant.now(FIXED_CLOCK);

        assertThat(schedule.nextAttemptAt(1, now)).isEqualTo(Instant.parse("2026-07-27T10:16:30Z"));
        assertThat(schedule.nextAttemptAt(2, now)).isEqualTo(Instant.parse("2026-07-27T10:20:30Z"));
        assertThat(schedule.nextAttemptAt(3, now)).isEqualTo(Instant.parse("2026-07-27T10:45:30Z"));
        assertThat(schedule.nextAttemptAt(9, now)).isEqualTo(Instant.parse("2026-07-27T10:45:30Z"));
    }

    @Test
    @DisplayName("an attempt count below one is treated as the first attempt")
    void degenerateAttemptCounts() {
        // Attempts are one-based because the claim increments before returning.
        // A zero could only come from a bug, and the useful response to a bug in
        // a retry counter is the shortest delay, not an index out of bounds.
        assertThat(schedule.delayAfter(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(schedule.delayAfter(-7)).isEqualTo(Duration.ofMinutes(1));
    }
}
