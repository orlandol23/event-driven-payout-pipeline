package io.github.orlandol23.payout.api.payout;

/**
 * Outcome of a create request: the payout, plus whether it already existed.
 *
 * <p>The caller needs to tell these apart to answer 201 or 200. Returning the
 * bare entity would force the web layer to guess, for example by comparing
 * timestamps, which is exactly the kind of inference that breaks quietly.
 *
 * @param replayed true when an earlier request with the same idempotency key
 *                 had already created this payout
 */
public record PayoutCreation(Payout payout, boolean replayed) {

    public static PayoutCreation created(Payout payout) {
        return new PayoutCreation(payout, false);
    }

    public static PayoutCreation replayed(Payout payout) {
        return new PayoutCreation(payout, true);
    }
}
