package io.github.orlandol23.payout.api.payout.web;

import io.github.orlandol23.payout.api.config.ClockConfig;
import io.github.orlandol23.payout.api.correlation.CorrelationId;
import io.github.orlandol23.payout.api.correlation.CorrelationIdProvider;
import io.github.orlandol23.payout.api.payout.CreatePayoutCommand;
import io.github.orlandol23.payout.api.payout.IdempotencyKeyReusedException;
import io.github.orlandol23.payout.api.payout.Payout;
import io.github.orlandol23.payout.api.payout.PayoutCreation;
import io.github.orlandol23.payout.api.payout.PayoutNotFoundException;
import io.github.orlandol23.payout.api.payout.PayoutService;
import io.github.orlandol23.payout.api.payout.PayoutStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web layer slice: status codes, headers and the exact shape of the error body.
 *
 * <p>A slice rather than a full {@code @SpringBootTest}, because none of these
 * assertions need a database. The service is mocked so each HTTP outcome can be
 * provoked directly; that the service behaves that way is covered by
 * {@code PayoutServiceTest} and {@code PayoutApiIT}.
 */
@WebMvcTest(PayoutController.class)
@Import({ClockConfig.class, CorrelationIdProvider.class})
class PayoutControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:15:30Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PayoutService payoutService;

    @Nested
    @DisplayName("POST /payouts")
    class Create {

        @Test
        @DisplayName("answers 201 with a Location header for a new payout")
        void createsPayout() throws Exception {
            Payout payout = payout(new BigDecimal("125.5000"), "BRL");
            given(payoutService.create(any())).willReturn(PayoutCreation.created(payout));

            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 125.50, "currency": "BRL"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/payouts/" + payout.getId()))
                    .andExpect(jsonPath("$.id").value(payout.getId().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.currency").value("BRL"))
                    .andExpect(jsonPath("$.attempts").value(0))
                    .andExpect(jsonPath("$.lastError").doesNotExist());
        }

        @Test
        @DisplayName("answers 200, not 201, when an idempotency key replayed an existing payout")
        void replayAnswersOk() throws Exception {
            Payout payout = payout(new BigDecimal("125.5000"), "BRL");
            given(payoutService.create(any())).willReturn(PayoutCreation.replayed(payout));

            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(PayoutController.IDEMPOTENCY_KEY_HEADER, "key-1")
                            .content("""
                                    {"amount": 125.50, "currency": "BRL"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Location", "/payouts/" + payout.getId()))
                    .andExpect(jsonPath("$.id").value(payout.getId().toString()));
        }

        @Test
        @DisplayName("passes the idempotency key and the correlation id down to the service")
        void forwardsHeadersToTheService() throws Exception {
            given(payoutService.create(any()))
                    .willReturn(PayoutCreation.created(payout(new BigDecimal("1.0000"), "USD")));

            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(PayoutController.IDEMPOTENCY_KEY_HEADER, "key-42")
                            .header(CorrelationId.HEADER, "trace-42")
                            .content("""
                                    {"amount": 1.00, "currency": "USD"}
                                    """))
                    .andExpect(status().isCreated());

            ArgumentCaptor<CreatePayoutCommand> captor = ArgumentCaptor.forClass(CreatePayoutCommand.class);
            verify(payoutService).create(captor.capture());
            assertThat(captor.getValue().idempotencyKey()).isEqualTo("key-42");
            assertThat(captor.getValue().correlationId()).isEqualTo("trace-42");
        }

        @Test
        @DisplayName("rejects an unknown currency with a field level problem detail")
        void rejectsUnknownCurrency() throws Exception {
            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 10.00, "currency": "XYZ"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:payout:error:validation-failed"))
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.instance").value("/payouts"))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("currency"));
        }

        @Test
        @DisplayName("rejects a non positive amount")
        void rejectsNonPositiveAmount() throws Exception {
            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 0, "currency": "USD"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("amount"))
                    .andExpect(jsonPath("$.errors[0].message").value("amount must be greater than zero"));
        }

        @Test
        @DisplayName("rejects more decimal places than the column can store")
        void rejectsTooManyDecimals() throws Exception {
            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 1.000005, "currency": "USD"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("amount"));
        }

        @Test
        @DisplayName("reports every invalid field at once instead of one per round trip")
        void reportsAllFieldsAtOnce() throws Exception {
            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": null, "currency": null}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors", hasSize(2)));
        }

        @Test
        @DisplayName("rejects an oversized idempotency key")
        void rejectsOversizedIdempotencyKey() throws Exception {
            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(PayoutController.IDEMPOTENCY_KEY_HEADER, "k".repeat(129))
                            .content("""
                                    {"amount": 10.00, "currency": "USD"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:payout:error:validation-failed"));
        }

        @Test
        @DisplayName("answers 422 when the idempotency key was already used for a different request")
        void answersUnprocessableOnIdempotencyMismatch() throws Exception {
            UUID existing = UUID.randomUUID();
            willThrow(new IdempotencyKeyReusedException("key-1", existing))
                    .given(payoutService).create(any());

            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(PayoutController.IDEMPOTENCY_KEY_HEADER, "key-1")
                            .content("""
                                    {"amount": 999.99, "currency": "USD"}
                                    """))
                    // 422, not 409: the request is understood and will fail the
                    // same way forever, so a retry is not the answer.
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:payout:error:idempotency-mismatch"))
                    .andExpect(jsonPath("$.title").value("Idempotency key reused"))
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.instance").value("/payouts"))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(jsonPath("$.timestamp").exists())
                    // Neither the key nor the winning payout id is echoed back:
                    // the caller knows its own key, and the id belongs to the
                    // earlier request.
                    .andExpect(content().string(not(containsString(existing.toString()))))
                    .andExpect(content().string(not(containsString("key-1"))));
        }

        @Test
        @DisplayName("answers malformed-request, not a Jackson stack trace, for broken JSON")
        void rejectsMalformedJson() throws Exception {
            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:payout:error:malformed-request"))
                    .andExpect(jsonPath("$.detail").value(
                            "The request body is missing or is not valid JSON matching the expected schema."));
        }

        @Test
        @DisplayName("answers 500 with no internal detail when the service blows up")
        void hidesInternalFailureDetail() throws Exception {
            willThrow(new IllegalStateException("connection pool exhausted at com.zaxxer.hikari"))
                    .given(payoutService).create(any());

            mockMvc.perform(post("/payouts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"amount": 10.00, "currency": "USD"}
                                    """))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type").value("urn:payout:error:internal-error"))
                    .andExpect(jsonPath("$.detail").value(
                            "The request could not be processed. Quote the correlation id when reporting this."));
        }
    }

    @Nested
    @DisplayName("GET /payouts/{id}")
    class GetById {

        @Test
        @DisplayName("returns the payout")
        void returnsPayout() throws Exception {
            Payout payout = payout(new BigDecimal("99.9900"), "EUR");
            given(payoutService.findById(payout.getId())).willReturn(payout);

            mockMvc.perform(get("/payouts/{id}", payout.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(payout.getId().toString()))
                    .andExpect(jsonPath("$.amount").value(99.99))
                    .andExpect(jsonPath("$.currency").value("EUR"))
                    .andExpect(jsonPath("$.createdAt").value("2026-07-27T10:15:30Z"));
        }

        @Test
        @DisplayName("answers 404 as a problem detail")
        void answersNotFound() throws Exception {
            UUID missing = UUID.randomUUID();
            given(payoutService.findById(missing)).willThrow(new PayoutNotFoundException(missing));

            mockMvc.perform(get("/payouts/{id}", missing))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:payout:error:payout-not-found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.instance").value("/payouts/" + missing))
                    .andExpect(jsonPath("$.correlationId").exists());
        }

        @Test
        @DisplayName("answers 400 for an id that is not a UUID")
        void answersBadRequestForMalformedId() throws Exception {
            mockMvc.perform(get("/payouts/{id}", "not-a-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:payout:error:malformed-request"));
        }
    }

    @Nested
    @DisplayName("cross cutting")
    class CrossCutting {

        @Test
        @DisplayName("echoes the caller's correlation id back")
        void echoesCallerCorrelationId() throws Exception {
            given(payoutService.findById(any())).willThrow(new PayoutNotFoundException(UUID.randomUUID()));

            mockMvc.perform(get("/payouts/{id}", UUID.randomUUID())
                            .header(CorrelationId.HEADER, "my-trace-id"))
                    .andExpect(header().string(CorrelationId.HEADER, "my-trace-id"))
                    .andExpect(jsonPath("$.correlationId").value("my-trace-id"));
        }

        @Test
        @DisplayName("replaces a correlation id that could forge a log line")
        void replacesUnsafeCorrelationId() throws Exception {
            given(payoutService.findById(any())).willThrow(new PayoutNotFoundException(UUID.randomUUID()));

            mockMvc.perform(get("/payouts/{id}", UUID.randomUUID())
                            .header(CorrelationId.HEADER, "id with spaces and : punctuation"))
                    .andExpect(header().string(CorrelationId.HEADER,
                            matchesPattern("^[0-9a-f-]{36}$")));
        }

        @Test
        @DisplayName("mints a correlation id when the caller sends none")
        void mintsCorrelationIdWhenAbsent() throws Exception {
            given(payoutService.findById(any())).willThrow(new PayoutNotFoundException(UUID.randomUUID()));

            mockMvc.perform(get("/payouts/{id}", UUID.randomUUID()))
                    .andExpect(header().string(CorrelationId.HEADER, matchesPattern("^[0-9a-f-]{36}$")));
        }

        @Test
        @DisplayName("an unsupported method is a problem detail too, not an HTML error page")
        void unsupportedMethodIsAProblemDetail() throws Exception {
            mockMvc.perform(patch("/payouts/{id}", UUID.randomUUID()))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.correlationId").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    private static Payout payout(BigDecimal amount, String currency) {
        return Payout.request(UUID.randomUUID(), null, amount, currency, "corr-1", NOW);
    }
}
