package io.github.orlandol23.payout.api.payout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The fingerprint of the request an idempotency key stands for.
 *
 * <p>An idempotency key promises "this is the same request again". Without a
 * fingerprint the API has to take that on trust, and a caller that reuses a key
 * with a different amount is quietly handed the first payout instead of being
 * told it made a mistake. Storing a hash of the request is what turns that from
 * a silent wrong answer into a 422.
 *
 * <p>A hash rather than the fields themselves: it is fixed width, it compares in
 * one column, and it does not duplicate data the row already holds. There is no
 * security claim here and no secret involved, so SHA-256 is chosen for being
 * ubiquitous and collision resistant, not for being cryptographic.
 *
 * <p>The canonical form is what makes the comparison honest. The amount is
 * normalised to the stored scale first, so {@code 10.5} and {@code 10.5000} are
 * one request rather than two, and the separator keeps
 * {@code 1.0000 + "USD"} from colliding with {@code 1.0000U + "SD"}.
 */
public final class IdempotencyFingerprint {

    /** Hex of a SHA-256 digest, and the width of {@code idempotency_fingerprint}. */
    public static final int LENGTH = 64;

    private static final String ALGORITHM = "SHA-256";
    private static final char SEPARATOR = '|';

    private IdempotencyFingerprint() {
    }

    public static String of(BigDecimal amount, String currency) {
        String canonical = amount.setScale(Payout.AMOUNT_SCALE, RoundingMode.UNNECESSARY).toPlainString()
                + SEPARATOR
                + currency;
        return HexFormat.of().formatHex(digest().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A fresh digest per call, because {@link MessageDigest} is not thread safe
     * and this runs on every create.
     *
     * <p>SHA-256 is required of every JRE, so the checked exception describes a
     * platform that cannot exist. It is rethrown unchecked rather than passed on
     * to callers who have nothing useful to do with it.
     */
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(ALGORITHM + " is required of every JRE", impossible);
        }
    }
}
