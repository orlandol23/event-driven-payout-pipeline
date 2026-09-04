package io.github.orlandol23.payout.worker.payout;

/**
 * Why a payout ended up on the dead letter topic.
 *
 * <p>Two reasons, and telling them apart is the point. A payout that was
 * rejected the first time it was tried is a bad request and someone should look
 * at the request. A payout that exhausted its attempts was a plausible request
 * that kept meeting a broken provider, and someone should look at the provider.
 * Collapsing both into "it failed" hands whoever drains this topic a pile they
 * have to re-diagnose one by one.
 */
public enum DeadLetterReason {

    /** A permanent failure. The first attempt was also the last. */
    PERMANENT("permanent"),

    /** Transient failures, retried until the attempt budget ran out. */
    EXHAUSTED("exhausted");

    private final String wireValue;

    DeadLetterReason(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * What travels in the {@code X-Failure-Kind} header.
     *
     * <p>Lower case and written out here rather than {@code name()}, because
     * {@code name()} is a Java identifier that happens to be readable and this
     * is a wire contract. Renaming the constant should not silently change what
     * a consumer of the topic is matching on.
     */
    public String wireValue() {
        return wireValue;
    }
}
