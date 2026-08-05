package io.github.orlandol23.payout.api.error;

/**
 * One field level validation failure, carried in the {@code errors} extension of
 * a validation problem.
 *
 * <p>RFC 7807 allows arbitrary extension members, which is the sanctioned way to
 * return the per-field breakdown a form needs while keeping the standard
 * envelope.
 *
 * <p>The rejected value is not included. It would help debugging, but this
 * endpoint carries payment data, and error bodies have a habit of ending up in
 * log aggregators and screenshots.
 *
 * @param field   JSON path of the offending field, or the parameter name
 * @param message what is wrong with it, safe to show to a caller
 */
public record ApiFieldError(String field, String message) {
}
