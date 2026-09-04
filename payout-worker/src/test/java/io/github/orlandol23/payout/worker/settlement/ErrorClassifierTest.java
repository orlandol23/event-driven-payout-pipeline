package io.github.orlandol23.payout.worker.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one decision that decides whether a payout is tried again.
 *
 * <p>Short tests for a short class, and the last one is the point of the whole
 * file: an exception nobody wrote a rule for must come back transient.
 */
class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @Test
    @DisplayName("a permanent settlement failure is permanent")
    void permanentIsPermanent() {
        assertThat(classifier.classify(new SettlementRejectedException("account closed")))
                .isEqualTo(FailureKind.PERMANENT);
        assertThat(classifier.classify(new PermanentSettlementException("unsupported currency")))
                .isEqualTo(FailureKind.PERMANENT);
    }

    @Test
    @DisplayName("a transient settlement failure is transient")
    void transientIsTransient() {
        assertThat(classifier.classify(new SettlementUnavailableException("provider is down")))
                .isEqualTo(FailureKind.TRANSIENT);
        assertThat(classifier.classify(new TransientSettlementException("rate limited")))
                .isEqualTo(FailureKind.TRANSIENT);
    }

    @Test
    @DisplayName("anything unrecognised is transient, which is the safe direction")
    void unknownFailuresAreTransient() {
        // The caller was already told 201. Calling an unknown failure permanent
        // fails a payout a retry might have settled; calling it transient costs
        // a few attempts and ends in the same dead letter if it really was
        // permanent. The attempt budget is what makes that affordable.
        assertThat(classifier.classify(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailureKind.TRANSIENT);
        assertThat(classifier.classify(new IOException("connection reset")))
                .isEqualTo(FailureKind.TRANSIENT);
        assertThat(classifier.classify(new IllegalStateException("pool exhausted")))
                .isEqualTo(FailureKind.TRANSIENT);
        assertThat(classifier.classify(new RuntimeException()))
                .isEqualTo(FailureKind.TRANSIENT);
    }

    @Test
    @DisplayName("the subtype decides, not the exception a cause was wrapped in")
    void classificationFollowsTheThrownType() {
        // A permanent failure carrying a transient looking cause is still
        // permanent: whoever threw it made the judgement, and unwrapping causes
        // here would quietly overrule them.
        SettlementRejectedException rejected = new SettlementRejectedException("rejected");
        rejected.initCause(new SocketTimeoutException("read timed out"));

        assertThat(classifier.classify(rejected)).isEqualTo(FailureKind.PERMANENT);
    }
}
