package io.github.orlandol23.payout.api.payout;

import io.github.orlandol23.payout.api.payout.events.PayoutEventPublisher;
import io.github.orlandol23.payout.contracts.PayoutRequested;
import io.github.orlandol23.payout.contracts.PayoutTopics;
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
    private final PayoutEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Constructor injection, so the dependencies are final, the object cannot
     * exist half built, and a unit test can construct it with no Spring context.
     */
    public PayoutService(PayoutRepository repository, PayoutEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
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
     *
     * <p>A {@code payout.requested} event is published only for a payout this
     * call actually created. A replay publishes nothing: the first call already
     * did, and a second event would hand the worker a second unit of work for a
     * payout that has one.
     */
    public PayoutCreation create(CreatePayoutCommand command) {
        if (command.idempotencyKey() != null) {
            Optional<Payout> alreadyCreated = repository.findByIdempotencyKey(command.idempotencyKey());
            if (alreadyCreated.isPresent()) {
                return replay(alreadyCreated.get(), command);
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
            announce(created);
            return PayoutCreation.created(created);
        } catch (DataIntegrityViolationException violation) {
            return recoverFromLostRace(command, violation);
        }
    }

    /**
     * Hands back the payout an idempotency key already created, but only if the
     * request behind the key has not changed.
     *
     * <p>An idempotency key says "this is the same request again". Returning the
     * first payout for a request that is not the same one would answer 200 with
     * a payout for an amount the caller never asked for, and the caller would
     * have no way of noticing. A 422 says the key is spent on something else.
     *
     * <p>A null fingerprint means the row predates V2, so there is nothing to
     * compare against. Unknown is not the same as different: the replay goes
     * through, exactly as it did before the column existed. The alternative
     * would be to fail every replay of every pre-migration payout, which is a
     * migration turning into an outage.
     */
    private PayoutCreation replay(Payout existing, CreatePayoutCommand command) {
        String fingerprint = IdempotencyFingerprint.of(command.amount(), command.currency());
        if (existing.getIdempotencyFingerprint() != null
                && !existing.getIdempotencyFingerprint().equals(fingerprint)) {
            log.warn("Idempotency key {} was already used for payout {} with a different request",
                    command.idempotencyKey(), existing.getId());
            throw new IdempotencyKeyReusedException(command.idempotencyKey(), existing.getId());
        }
        log.info("Replaying payout {} for idempotency key {}", existing.getId(), command.idempotencyKey());
        return PayoutCreation.replayed(existing);
    }

    /**
     * Publishes {@code payout.requested} after the row is durable, and never
     * fails the request if that publish does not work.
     *
     * <p><strong>There is no outbox yet, and this is where that shows.</strong>
     * The insert is committed and the publish is a separate, non-transactional
     * call, so a broker that is down between the two leaves a {@code PENDING}
     * row with no event. The API still answers 201, which stays truthful: 201
     * means the request is durable and queued, and the row <em>is</em> the
     * queue. Nothing republishes the event; the worker's claim scan reads
     * {@code payouts} directly and picks the row up, which is what makes Kafka a
     * latency optimisation here rather than the source of truth.
     *
     * <p>Publishing before the commit would be worse in the direction that
     * matters: the worker could receive an event for a row that never lands.
     * Losing an event only delays a payout that the database still knows about.
     */
    private void announce(Payout created) {
        try {
            eventPublisher.publish(new PayoutRequested(
                    created.getId(),
                    created.getAmount(),
                    created.getCurrency(),
                    created.getCorrelationId(),
                    created.getCreatedAt()));
        } catch (RuntimeException failure) {
            // Caught here rather than left to the publisher, because this is the
            // boundary that must not fail: a broker problem is not a client
            // error and must not turn a durable payout into a 500.
            log.error("Payout {} is durable but its {} event was not published [correlationId={}]",
                    created.getId(), PayoutTopics.PAYOUT_REQUESTED, created.getCorrelationId(), failure);
        }
    }

    /**
     * Handles losing the insert race to a concurrent request with the same key.
     *
     * <p>The winner's row is now committed, so re-reading returns it and both
     * callers get the same payout. If the re-read finds nothing, the violation
     * came from some other constraint and hiding it would be a bug, so it is
     * rethrown untouched.
     *
     * <p>The winner goes through the same fingerprint check as any other replay.
     * Two concurrent requests carrying one key and two different bodies are the
     * same mistake as two sequential ones, and the loser of the race must not be
     * the only caller that gets away with it.
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
                    return replay(winner, command);
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
