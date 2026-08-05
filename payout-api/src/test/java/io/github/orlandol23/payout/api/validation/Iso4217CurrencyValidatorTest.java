package io.github.orlandol23.payout.api.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class Iso4217CurrencyValidatorTest {

    private final Iso4217CurrencyValidator validator = new Iso4217CurrencyValidator();

    @ParameterizedTest
    @ValueSource(strings = {"BRL", "USD", "EUR", "JPY", "GBP"})
    @DisplayName("accepts real ISO 4217 codes")
    void acceptsRealCodes(String code) {
        assertThat(validator.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "ABC", "BR", "BRLL", "", " ", "123"})
    @DisplayName("rejects anything that is not a real code, including well formed fakes")
    void rejectsFakes(String code) {
        assertThat(validator.isValid(code, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"brl", "Usd", "eUr"})
    @DisplayName("rejects lower case rather than silently upper casing it")
    void rejectsLowerCase(String code) {
        assertThat(validator.isValid(code, null)).isFalse();
    }

    @Test
    @DisplayName("null passes, because requiredness is @NotNull's job")
    void nullIsSomeoneElsesProblem() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
