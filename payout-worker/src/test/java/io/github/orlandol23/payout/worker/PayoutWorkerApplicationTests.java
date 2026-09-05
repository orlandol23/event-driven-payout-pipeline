package io.github.orlandol23.payout.worker;

import io.github.orlandol23.payout.worker.payout.PayoutPoller;
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
 * reach a broker and {@code poll-enabled=false} keeps the claim scan from
 * reaching for a database, so this stays a pure "does the context wire up" test
 * with no infrastructure at all. That the listener actually consumes is
 * {@code PayoutRequestedListenerTest}'s job, against an embedded broker; that
 * the statements are right is {@code PayoutWorkerIT}'s, against a real
 * PostgreSQL.
 *
 * <p>It is worth more than it looks. Every settings record is bound here, so a
 * property renamed in {@code application.yml} and not in the record fails this
 * test rather than a production start.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "payout.worker.poll-enabled=false"
})
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

    @Test
    @DisplayName("payout.worker.poll-enabled=false leaves the claim scan out of the context entirely")
    void theClaimScanCanBeTurnedOff() {
        // Not "the scheduled method returns early": the bean is not there, so
        // there is no scheduled task at all. That is what makes it safe to run
        // this context with no database.
        assertThat(context.getBeansOfType(PayoutPoller.class)).isEmpty();
    }
}
