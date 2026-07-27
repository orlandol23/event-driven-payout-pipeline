package io.github.orlandol23.payout.api.payout.web;

import io.github.orlandol23.payout.api.correlation.CorrelationIdProvider;
import io.github.orlandol23.payout.api.payout.CreatePayoutCommand;
import io.github.orlandol23.payout.api.payout.Payout;
import io.github.orlandol23.payout.api.payout.PayoutCreation;
import io.github.orlandol23.payout.api.payout.PayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/payouts")
public class PayoutController {

    /**
     * Header carrying the idempotency key. Same name Stripe, Adyen and the IETF
     * idempotency draft use, so integrators already know what it does.
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final PayoutService payoutService;
    private final CorrelationIdProvider correlationIdProvider;

    public PayoutController(PayoutService payoutService, CorrelationIdProvider correlationIdProvider) {
        this.payoutService = payoutService;
        this.correlationIdProvider = correlationIdProvider;
    }

    /**
     * Accepts a payout request.
     *
     * <p>Answers <strong>201 Created</strong> for a new payout and <strong>200
     * OK</strong> when an idempotency key replayed an existing one. Both carry
     * the same body and {@code Location}, so a retrying client does not need to
     * branch, but a client that cares can tell whether its retry actually did
     * anything.
     *
     * <p>201 does not mean the money moved. It means the request is durable and
     * queued. The payout is {@code PENDING}; settlement is the worker's job, and
     * {@code GET /payouts/{id}} is how a caller follows it.
     */
    @PostMapping
    public ResponseEntity<PayoutResponse> create(
            @Valid @RequestBody CreatePayoutRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            @Size(max = Payout.IDEMPOTENCY_KEY_MAX_LENGTH,
                    message = "Idempotency-Key must be at most 128 characters")
            String idempotencyKey) {

        PayoutCreation creation = payoutService.create(new CreatePayoutCommand(
                request.amount(),
                request.currency(),
                idempotencyKey,
                correlationIdProvider.current()));

        PayoutResponse body = PayoutResponse.from(creation.payout());
        URI location = URI.create("/payouts/" + body.id());

        return creation.replayed()
                ? ResponseEntity.ok().location(location).body(body)
                : ResponseEntity.created(location).body(body);
    }

    /**
     * Reads a payout.
     *
     * <p>Spring converts the path segment to a {@link UUID} for us; a malformed
     * id fails binding and never reaches the service, which is why there is no
     * parsing here.
     */
    @GetMapping("/{id}")
    public PayoutResponse getById(@PathVariable UUID id) {
        return PayoutResponse.from(payoutService.findById(id));
    }
}
