package io.github.orlandol23.payout.worker.payout;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When a transient failure gets its next attempt.
 *
 * <p>One minute, then five, then thirty, then thirty for as long as the attempt
 * budget lasts. The same fixed ladder the TypeScript original used, and fixed
 * rather than computed for a reason worth writing down: a table of three
 * durations can be read, argued about and changed by whoever is on call, while
 * {@code base * 2^n} has to be evaluated in someone's head before anyone can say
 * whether the third retry lands in two minutes or two hours.
 *
 * <p>The clamp is what keeps a long schedule from running away. Doubling past
 * the end of the list would put the last attempts hours apart, long after the
 * transient condition either cleared or turned out to be permanent.
 *
 * <p>No jitter, deliberately. Jitter earns its place when many clients retry
 * against one dependency in lockstep; here the retry times are already spread by
 * whenever each payout happened to fail, and randomness would make the one thing
 * this class promises, an exact {@code next_attempt_at}, unassertable.
 */
@Component
public class BackoffSchedule {

    private static final List<Duration> LADDER = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30));

    /**
     * The delay before the attempt following {@code attempt}.
     *
     * <p>Attempts are one-based, as the row counts them: after the first attempt
     * fails the wait is one minute, after the second five, and every later one
     * is clamped to thirty.
     */
    public Duration delayAfter(int attempt) {
        int index = Math.max(attempt, 1) - 1;
        return LADDER.get(Math.min(index, LADDER.size() - 1));
    }

    /** The instant to write into {@code next_attempt_at}. */
    public Instant nextAttemptAt(int attempt, Instant now) {
        return now.plus(delayAfter(attempt));
    }
}
