package io.github.orlandol23.payout.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one claim this module makes: the event survives a JSON round trip
 * unchanged.
 *
 * <p>Worth a test rather than a comment, because the record carries the two
 * types that quietly lose information in JSON. {@code BigDecimal} becomes a
 * double if anything in the chain treats it as a number rather than a decimal,
 * and {@code Instant} becomes an epoch fraction unless dates are written as
 * ISO-8601 strings. Money that arrives at the worker as {@code 125.49999999} is
 * the kind of bug that is found in production.
 *
 * <p>The mapper here is configured the way the serializers on both sides are, so
 * this asserts the contract, not Jackson's defaults.
 */
class PayoutRequestedTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("binds through the canonical constructor with no annotations")
    void roundTripsThroughJackson() throws Exception {
        PayoutRequested event = new PayoutRequested(
                UUID.fromString("9f1c4a2e-0b3d-4e6f-8a1b-2c3d4e5f6a7b"),
                new BigDecimal("125.5000"),
                "BRL",
                "demo-trace-1",
                Instant.parse("2026-07-27T10:15:30Z"));

        String json = mapper.writeValueAsString(event);
        PayoutRequested parsed = mapper.readValue(json, PayoutRequested.class);

        assertThat(parsed).isEqualTo(event);
    }

    @Test
    @DisplayName("keeps the amount a decimal and the instant an ISO-8601 string on the wire")
    void serialisesMoneyAndTimeWithoutLosingPrecision() throws Exception {
        PayoutRequested event = new PayoutRequested(
                UUID.randomUUID(),
                new BigDecimal("125.5000"),
                "BRL",
                "demo-trace-1",
                Instant.parse("2026-07-27T10:15:30Z"));

        String json = mapper.writeValueAsString(event);

        assertThat(json)
                .contains("\"amount\":125.5000")
                .contains("\"requestedAt\":\"2026-07-27T10:15:30Z\"");
        assertThat(mapper.readValue(json, PayoutRequested.class).amount())
                .isEqualByComparingTo("125.5000");
    }

    @Test
    @DisplayName("topic names are the ones both services subscribe to")
    void topicNamesAreStable() {
        assertThat(PayoutTopics.PAYOUT_REQUESTED).isEqualTo("payout.requested");
        assertThat(PayoutTopics.PAYOUT_REQUESTED_DLT).isEqualTo("payout.requested.dlt");
    }
}
