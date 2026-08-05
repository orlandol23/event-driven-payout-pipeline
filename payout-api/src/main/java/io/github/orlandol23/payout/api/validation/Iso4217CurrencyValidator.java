package io.github.orlandol23.payout.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

public class Iso4217CurrencyValidator implements ConstraintValidator<Iso4217Currency, String> {

    /**
     * Resolved once from the JDK's own ISO 4217 table, so the list stays correct
     * across JDK updates instead of drifting from a hand maintained constant.
     * {@link Currency#getCurrencyCode()} always returns upper case, which is what
     * makes this check case sensitive.
     */
    private static final Set<String> ISO_4217_CODES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Null passes. Whether a value is required is {@code @NotNull}'s decision,
     * not this constraint's; conflating the two makes it impossible to have an
     * optional-but-validated field.
     *
     * <p>Lower case is rejected rather than silently upper cased. For a payments
     * API, telling the caller their request was malformed beats quietly
     * reinterpreting it.
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || ISO_4217_CODES.contains(value);
    }
}
