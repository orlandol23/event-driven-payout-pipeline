package io.github.orlandol23.payout.api.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final PayoutRepository repository;
    private final Clock clock;

    /**
     * Constructor injection, so the dependencies are final, the object cannot
     * exist half built, and a unit test can construct it with no Spring context.
     */
    public PayoutService(PayoutRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Accepts a payout request and stores it as {@code PENDING}.
     *
     * <p><strong>Not annotated {@code @Transactional}, and that is deliberate.</strong>
     * The recovery path below reads the database <em>after</em> catching a
     * constraint violation. Inside an active transaction that read would fail,
     * because a violated transaction is already marked rollback-only and Spring
     * refuses to do further work in it. Each repository call here runs in its own
     * transaction, so the recovery read starts clean. The whole operation is a
     * single insert, so there is nothing that needs wrapping anyway.
     *
     * <p>Note what is <em>not</em> here: a "does this key exist yet" check used to
     * decide whether to insert. Between that check and the insert, a second
     * request can slip in. The lookup below is a fast path that avoids a
     * guaranteed-to-fail insert in the common replay case; correctness comes from
     * {@code ux_payouts_idempotency_key} and the catch block, not from the check.
     */
    public PayoutCreation create(CreatePayoutCommand command) {
        if (command.idempotencyKey() != null) {
            Optional<Payout> alreadyCreated = repository.findByIdempotencyKey(command.idempotencyKey());
            if (alreadyCreated.isPresent()) {
                Payout existing = alreadyCreated.get();
                log.info("Replaying payout {} for idempotency key {}", existing.getId(), command.idempotencyKey());
                return PayoutCreation.replayed(existing);
            }
        }

        Instant now = Instant.now(clock);
        Payout payout = Payout.request(
                UUID.randomUUID(),
                command.idempotencyKey(),
                command.amount(),
                command.currency(),
                command.correlationId(),
                now);

        try {
            // saveAndFlush, not save: we want the INSERT to hit the database now,
            // so the unique index violation surfaces here where we can recover,
            // instead of at some later commit outside this method.
            Payout created = repository.saveAndFlush(payout);
            log.info("Created payout {} for {} {}", created.getId(), created.getAmount(), created.getCurrency());
            return PayoutCreation.created(created);
        } catch (DataIntegrityViolationException violation) {
            return recoverFromLostRace(command, violation);
        }
    }

    /**
     * Handles losing the insert race to a concurrent request with the same key.
     *
     * <p>The winner's row is now committed, so re-reading returns it and both
     * callers get the same payout. If the re-read finds nothing, the violation
     * came from some other constraint and hiding it would be a bug, so it is
     * rethrown untouched.
     */
    private PayoutCreation recoverFromLostRace(CreatePayoutCommand command,
                                               DataIntegrityViolationException violation) {
        if (command.idempotencyKey() == null) {
            throw violation;
        }
        return repository.findByIdempotencyKey(command.idempotencyKey())
                .map(winner -> {
                    log.info("Lost insert race for idempotency key {}, returning payout {}",
                            command.idempotencyKey(), winner.getId());
                    return PayoutCreation.replayed(winner);
                })
                .orElseThrow(() -> violation);
    }

    /**
     * Reads a payout by id.
     *
     * <p>{@code readOnly = true} lets Hibernate skip dirty checking and tells the
     * driver this will not write, which matters once reads are routed to a
     * replica.
     */
    @Transactional(readOnly = true)
    public Payout findById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new PayoutNotFoundException(id));
    }
}
