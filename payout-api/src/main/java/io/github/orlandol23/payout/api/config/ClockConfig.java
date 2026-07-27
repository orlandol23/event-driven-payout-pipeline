package io.github.orlandol23.payout.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Time as an injected dependency rather than a static call.
     *
     * <p>{@code Instant.now()} inside a service is untestable: a test can only
     * assert "roughly now" and turns flaky on a slow machine. With a {@link Clock}
     * bean, tests substitute {@link Clock#fixed} and assert exact timestamps, and
     * day 3's backoff logic becomes testable without sleeping.
     *
     * <p>UTC, not the system zone. Every timestamp is stored as
     * {@code timestamptz} and compared across services that may not share a
     * region; picking up whatever zone the container happens to have is how
     * timelines end up an hour apart twice a year.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
