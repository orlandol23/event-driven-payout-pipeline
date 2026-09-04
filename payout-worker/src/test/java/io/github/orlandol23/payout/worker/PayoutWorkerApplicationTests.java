package io.github.orlandol23.payout.worker;

import io.github.orlandol23.payout.worker.payout.PayoutRequestedListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the thing the worker can most easily get wrong: not starting.
 *
 * <p>{@code auto-startup=false} keeps the listener container from trying to
 * reach a broker, so this stays a pure "does the context wire up" test with no
 * infrastructure at all. That the listener actually consumes is
 * {@code PayoutRequestedListenerTest}'s job, against an embedded broker.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class PayoutWorkerApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the worker context starts")
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("payout-worker");
    }

    @Test
    @DisplayName("the payout.requested listener is registered")
    void theListenerIsWired() {
        assertThat(context.getBeansOfType(PayoutRequestedListener.class)).hasSize(1);
    }
}
