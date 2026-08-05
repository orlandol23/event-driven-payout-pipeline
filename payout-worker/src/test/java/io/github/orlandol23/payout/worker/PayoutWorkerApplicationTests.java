package io.github.orlandol23.payout.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one thing the worker can get wrong on day 1: not starting.
 *
 * <p>Thin, and honestly so. There is no consumer logic to test yet. The value is
 * that a broken dependency or a bad application.yml fails the build now instead
 * of on day 2 when there is real code to blame.
 */
@SpringBootTest
class PayoutWorkerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the worker context starts")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("payout-worker");
    }
}
