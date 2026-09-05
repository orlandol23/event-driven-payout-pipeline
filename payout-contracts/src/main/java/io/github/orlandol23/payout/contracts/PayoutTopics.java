package io.github.orlandol23.payout.contracts;

/**
 * Topic names, in one place, on the classpath of both services.
 *
 * <p>A topic name is a wire contract exactly like the event body is. Written as
 * a literal in the producer and again in the consumer, the two drift the first
 * time somebody renames one of them, and the failure is silence: the consumer
 * subscribes to a topic nothing publishes to and simply never wakes up. Here the
 * compiler catches the rename.
 */
public final class PayoutTopics {

    /** A payout row exists and is {@code PENDING}. Keyed by payout id. */
    public static final String PAYOUT_REQUESTED = "payout.requested";

    /**
     * Where a {@code payout.requested} event goes when it can never succeed.
     *
     * <p>The worker publishes here when a payout is rejected permanently or has
     * exhausted its attempts. Declared next to the topic it shadows so the name
     * is decided once, rather than coined twice under pressure.
     */
    public static final String PAYOUT_REQUESTED_DLT = "payout.requested.dlt";

    private PayoutTopics() {
    }
}
