package io.github.orlandol23.payout.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Time as an injected dependency rather than a static call, for the same
     * reason as in {@code payout-api} and with more riding on it here.
     *
     * <p>Every timestamp the worker writes is an argument to a SQL statement:
     * the lock it takes, the moment it updated a row, and the moment a retry
     * becomes due. With {@code now()} in the statement or {@code Instant.now()}
     * in the code, a test of the backoff schedule can only assert "roughly five
     * minutes from roughly now", or it has to sleep. With a fixed {@link Clock}
     * it asserts the exact instant that lands in the column.
     *
     * <p>UTC, not the system zone: the columns are {@code timestamptz} and the
     * two services must agree on what "now" means whatever region they run in.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
