package io.github.orlandol23.payout.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated value must be an ISO 4217 alphabetic currency code.
 *
 * <p>A custom constraint rather than {@code @Pattern("^[A-Z]{3}$")}, because the
 * pattern happily accepts {@code XYZ}. Rejecting an unknown currency at the edge
 * is much cheaper than discovering it downstream, when the money has already
 * moved.
 */
@Documented
@Constraint(validatedBy = Iso4217CurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Iso4217Currency {

    String message() default "must be a valid ISO 4217 currency code, upper case";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
