package io.github.orlandol23.payout.worker.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The consumer side of the correlation id: read it off a record header, or mint
 * one.
 *
 * <p>Deliberately a copy of the API's class rather than a shared one. This is
 * two constants and one regular expression; promoting it to
 * {@code payout-contracts} would widen that module past the rule the README sets
 * for it, which is that it holds the wire events and nothing else. If day 4's
 * observability work turns correlation handling into more than this, it earns
 * its own shared module then. Duplicating one small rule is cheaper than
 * loosening the boundary that keeps the two services independent.
 *
 * <p>The validation is not paranoia about our own producer. A Kafka topic is
 * writable by anything with credentials, and this value goes straight into log
 * lines, so a header carrying a newline would let a producer forge log entries.
 * Anything that does not match is replaced rather than sanitised.
 */
public final class CorrelationId {

    /** Record header carrying the id, same name the HTTP edge uses. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key, so every log line about one record is tagged with it. */
    public static final String MDC_KEY = "correlationId";

    /** Matches the {@code correlation_id} column width. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9_-]{1,%d}$".formatted(MAX_LENGTH));

    private CorrelationId() {
    }

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
