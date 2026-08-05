package io.github.orlandol23.payout.api.error;

import java.net.URI;

/**
 * Stable {@code type} identifiers for RFC 7807 problem responses.
 *
 * <p>These are the values clients should branch on. The {@code title} and
 * {@code detail} strings are for humans and may be reworded at any time; the
 * type URI is part of the contract and will not change meaning.
 *
 * <p>URNs rather than {@code https://} URLs on purpose. RFC 7807 lets the type
 * be any URI, and a URL that 404s is worse than one that was never meant to be
 * fetched. These become documentation links the day there is documentation to
 * link to.
 */
public final class ProblemTypes {

    private static final String PREFIX = "urn:payout:error:";

    /** Request body or parameters failed Bean Validation. */
    public static final URI VALIDATION_FAILED = URI.create(PREFIX + "validation-failed");

    /** Body was absent, was not valid JSON, or a value had the wrong type. */
    public static final URI MALFORMED_REQUEST = URI.create(PREFIX + "malformed-request");

    /** No payout exists with the requested id. */
    public static final URI PAYOUT_NOT_FOUND = URI.create(PREFIX + "payout-not-found");

    /** The request collided with existing state. */
    public static final URI CONFLICT = URI.create(PREFIX + "conflict");

    /** Anything unhandled. Details are logged, never returned. */
    public static final URI INTERNAL_ERROR = URI.create(PREFIX + "internal-error");

    private ProblemTypes() {
    }
}
