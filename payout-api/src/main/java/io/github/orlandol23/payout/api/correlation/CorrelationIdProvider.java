package io.github.orlandol23.payout.api.correlation;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Reads the correlation id for the current thread.
 *
 * <p>A one-line bean around a static MDC lookup, so callers depend on an
 * injected collaborator they can stub instead of reaching into a thread local
 * themselves. The worker fills the same MDC key from the {@code payout.requested}
 * record header, so a log line either side of the broker reads the same.
 */
@Component
public class CorrelationIdProvider {

    /**
     * Falls back to a fresh id rather than returning null. The correlation id is
     * a NOT NULL column, and a code path that somehow ran outside the filter
     * should still produce a persistable payout instead of a 500.
     */
    public String current() {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        return correlationId != null ? correlationId : CorrelationId.generate();
    }
}
