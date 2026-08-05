package io.github.orlandol23.payout.api.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Naming and validation rules for the correlation id, shared by the filter and
 * the provider.
 *
 * <p>The same id ends up in the log MDC, in the {@code payouts} row, in the
 * error response body and, from day 4, in a Kafka header, so that one value
 * follows a payout from the HTTP request that created it all the way to the
 * worker that settles it.
 */
public final class CorrelationId {

    /** Request and response header carrying the id. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key, so every log line on the request thread is tagged with it. */
    public static final String MDC_KEY = "correlationId";

    /** Matches the {@code correlation_id} column width. */
    public static final int MAX_LENGTH = 64;

    /**
     * Deliberately narrow. The value is echoed back in a response header and
     * written into logs, so anything that could carry a newline, a control
     * character or log-forging punctuation is refused rather than sanitised.
     */
    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9_-]{1,%d}$".formatted(MAX_LENGTH));

    private CorrelationId() {
    }

    /**
     * Takes the caller's id when it is safe to reuse, otherwise mints a new one.
     *
     * <p>Accepting a caller supplied id is what lets a trace span several
     * services. Refusing a malformed one keeps a hostile caller from writing
     * arbitrary text into our logs.
     */
    public static String resolve(String candidate) {
        if (candidate != null && ACCEPTED.matcher(candidate).matches()) {
            return candidate;
        }
        return generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
