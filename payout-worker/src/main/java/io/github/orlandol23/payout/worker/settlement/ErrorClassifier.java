package io.github.orlandol23.payout.worker.settlement;

import org.springframework.stereotype.Component;

/**
 * Decides whether a failure is worth another attempt.
 *
 * <p>One rule, and one deliberate default. A {@link PermanentSettlementException}
 * is permanent, a {@link TransientSettlementException} is transient, and
 * <strong>anything else is treated as transient</strong>.
 *
 * <p>That default is the interesting part. An exception nobody anticipated is,
 * by definition, one whose permanence is unknown, and the two ways of being
 * wrong are not symmetric. Calling an unknown failure permanent fails a payout
 * that a retry would have settled, and the caller was already told 201. Calling
 * it transient costs a few retries and, if it really was permanent, ends in the
 * same dead letter a few minutes later. The attempt budget is what makes the
 * safe direction affordable: an infinite retry loop would not be a safe default
 * at all, it would be a payout stuck forever plus a busy database.
 *
 * <p>A component rather than a static method, because the day this needs a real
 * provider's error codes it gains dependencies, and every caller already has it
 * injected.
 */
@Component
public class ErrorClassifier {

    public FailureKind classify(Throwable failure) {
        if (failure instanceof PermanentSettlementException) {
            return FailureKind.PERMANENT;
        }
        return FailureKind.TRANSIENT;
    }
}
