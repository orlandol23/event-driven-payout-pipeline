# Roadmap

What this pipeline is, what would make it a reference for the pattern it
demonstrates rather than a demonstration of it, and in what order. Written
2026-09-07 from the README's "Known limitations", the audit in
[`AUDIT-2026-09.md`](AUDIT-2026-09.md), and the owner's decisions.

Nothing here is described as done until the PR that did it is linked. The
five-day roadmap in the README stays as the record of how the project was
built; this file is what comes after it.

## Where it stands (2026-09-07)

Three Maven modules, Java 21, Spring Boot 3.5. An HTTP intake with RFC 7807
errors and body-fingerprinted idempotency, a Kafka producer and consumer keyed
by payout id, a worker that claims rows with a conditional `UPDATE ...
RETURNING`, fences every later transition on a lock token, retries with a
taxonomy and backoff, and dead-letters what it gives up on. A poller that makes
the table, not Kafka, the source of truth. 121 unit and slice tests without
Docker; 36 Testcontainers integration tests with it. CI runs `mvn verify` with
the daemon present.

The guarantee is stated honestly since PR #6: at-least-once delivery to the
provider, exactly-once effect provided the provider deduplicates on the payout
id. That sentence is the product.

## What separates a demonstration from a reference

A reviewer who opens a payout pipeline looks for, in order:

1. **Can it lose money, and can it pay twice.** Fenced now; the remaining risk
   is configuration that describes an impossible lock window.
2. **Can I see what it is doing.** Today: logs with a correlation id. No
   metrics, no traces, no dashboard.
3. **What happens to the failures.** Today: they land on a dead-letter topic
   and sit there.
4. **Can it be deployed by someone who is not the author.** Today: a
   docker-compose for a laptop; no deployment design.
5. **Who is allowed to call it.** Today: anyone; authentication is out of scope
   and said so.

## Phase 4: observability (the README's Day 4)

- [ ] Actuator with health groups: liveness, readiness (database reachable,
  broker reachable), and a worker-specific indicator that reports the age of
  the oldest `PENDING` row past its `next_attempt_at`.
- [ ] Micrometer counters and timers: claims, settlements by outcome
  (confirmed, transient, permanent), lost-lock events (the WARN/ERROR lines
  from PR #6 become a metric), poller sweeps and what each found, dead letters
  published and failed to publish.
- [ ] Prometheus scrape endpoint; a Grafana dashboard JSON committed under
  `docs/observability/`, so the picture is reproducible.
- [ ] JSON structured logs with the correlation id and payout id as fields, not
  interpolated text.
- [ ] OpenTelemetry traces across intake, Kafka, worker and gateway, one trace
  per payout. The correlation id is already carried in a record header; the
  trace context should ride alongside it.

**DoD:** a lost lock, a stuck payout and a broker outage are each visible on
the dashboard within one scrape interval, without reading logs.

## Phase 5: operations

- [ ] Boot-time validation that `stale-lock > poll-batch-size × settlement
  timeout`, failing the start with a message that names the three values. Closes
  the open half of AUDIT H3.
- [ ] A replay tool for `payout.requested.dlt`: read a dead letter, show its
  diagnosis headers, and re-enqueue a corrected payout on `payout.requested`
  under a new idempotency key with a link to the original. Today the topic is
  somewhere to look, not somewhere work gets done.
- [ ] Idempotency key retention: a documented policy (how long a key is kept,
  what a replay after expiry does) and the job that enforces it. The README
  names this as an open question; it needs an answer before real volume.
- [ ] Rate limit and body-size limit on `POST /payouts`.
- [ ] Deployment design (the README's Day 5): one document describing a
  three-broker Kafka with `min.insync.replicas=2` so `acks=all` means what it
  reads like, provisioned topics with partition counts and retention (no
  auto-create), a managed PostgreSQL with the migration order for V2-style
  changes (`NOT VALID`, `CONCURRENTLY`), and how the API and the worker scale
  independently.

**DoD:** a new operator can deploy from the document, and a dead letter can be
replayed without touching the database by hand.

## Phase 6: hardening the edges

- [ ] `Idempotency-Key` charset validated with the same regex discipline as
  `CorrelationId`, and never logged raw.
- [ ] Structural test on the idempotency fingerprint: fails when
  `PayoutRequested` gains a field the fingerprint does not cover.
- [ ] `handleTypeMismatch` stops reflecting the raw path value.
- [ ] `maven-wrapper.properties` with `distributionSha256Sum`.
- [ ] Time-ordered primary keys (UUIDv7) if write volume ever makes the B-tree
  fragmentation the README describes measurable. Not before.
- [ ] The outbox, or a documented decision not to have one. The README explains
  what its absence costs and why the poller bounds it; a reviewer will still
  ask. Either build it or turn the explanation into an ADR.

## Phase 7: a caller

- [ ] Authentication on the intake (API keys per caller is enough), and with
  it the idempotency key scoped by caller, which is the fix the audit deferred
  because there was no caller to scope by.
- [ ] `GET /payouts` list endpoint with keyset pagination, scoped to the caller.

## Out of scope, on purpose

A real settlement provider (the simulated gateway's documented failure modes are
what let reviewers exercise every path), multi-currency conversion, a UI. The
project is about the queue, and its value is that the queue's guarantee is
stated precisely and proven by tests that fail without the fix.

## Principles

1. Nothing ticked by intention. A box closes in the PR that closes it.
2. The table is the source of truth; Kafka is a latency optimisation. Any
   change that assumes the event always arrives breaks the guarantee.
3. Every guarantee in the README has a test that fails without the code that
   provides it.
4. Failure modes are documented before a reviewer discovers them.
