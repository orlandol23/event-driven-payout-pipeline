# payout-platform

![CI](https://github.com/orlandol23/event-driven-payout-pipeline/actions/workflows/ci.yml/badge.svg)

An asynchronous payout pipeline in Java 21 and Spring Boot 3: an HTTP edge that
accepts payout requests, and a worker that settles each of them once, and can
say precisely where that guarantee comes from and where it stops. The short
version: at-least-once delivery to the provider, exactly-once effect, given a
provider that deduplicates on the payout id. The long version is
[below](#what-exactly-once-actually-means-here).

> **Status: day 3 of 5.** The API, the schema, the idempotency guarantee, the
> Kafka hop and the worker that claims, settles, retries and dead letters are
> implemented and tested. Settlement is simulated: no money moves anywhere in
> this repository. Observability and the AWS deployment notes are not written
> yet. See [Honest scope](#honest-scope) for the precise line between the two.

## Why this project exists

I previously designed an asynchronous write queue in TypeScript: atomic claim,
exponential backoff, a transient versus permanent error taxonomy, and idempotency
enforced by a partial unique index.

This repository ports that same architecture to Java, Spring Boot and Kafka. The
point is not to learn Spring. The point is to show that the pattern is a property
of the design, not of the runtime, and that moving it across stacks changes the
syntax and almost nothing else.

The domain is payouts because money makes the guarantees non negotiable. A
duplicated cache entry is an annoyance. A duplicated payout is a refund request.

## Architecture

```
     client
       |
       | POST /payouts            GET /payouts/{id}
       v
+---------------+                              +-----------------+
|  payout-api   |  --- payout.requested --->   |  payout-worker  | --- payout.requested.dlt -->
|  Spring Boot  |  Kafka, keyed by payout id   |   Spring Boot   |     permanent + exhausted
+---------------+                              +-----------------+
       |                                                |
       |  INSERT status=PENDING        claim, settle, retry, and scan
       |                                the table for what Kafka missed
       |                                                |
       +--------------->  PostgreSQL  <-----------------+
                       partial unique index
                         = idempotency
```

The flow:

1. `POST /payouts` validates the request, writes it as `PENDING`, and publishes
   `payout.requested` keyed by payout id.
2. The worker consumes, claims the row atomically (one conditional `UPDATE`,
   which only a `PENDING` row that is due, or a `PROCESSING` row whose lock has
   gone stale, can match), settles it, and moves it to `CONFIRMED` or `FAILED`.
3. A transient failure (network, timeout, 5xx) is retried after 1, 5 then 30
   minutes until the attempt budget runs out. A permanent failure (validation,
   4xx) goes straight to the dead letter topic with no retry.
4. Redelivering the same event does no work twice.

All four are implemented. Step 2's settlement is a simulated provider with
injectable, deterministic failure modes, which is what makes steps 3 and 4
demonstrable from a `curl` command rather than merely described; see
[Settlement is simulated](#settlement-is-simulated). No money moves.

### There is no outbox, and here is what that costs

The insert commits, then the event is published. They are two operations, not
one, and nothing makes them atomic. If the broker is unreachable in the gap, the
row exists as `PENDING`, the event does not, and the request still answers
**201**.

That answer stays truthful, because 201 has never meant the money moved: it
means the request is durable and queued, and the row *is* the queue. The publish
failure is logged at ERROR with the correlation id, and nothing republishes it.

The bound on the damage is the claim scan. The worker sweeps `payouts` for
claimable rows every `payout.worker.poll-interval` regardless of what arrived
over Kafka, so an event lost here delays a payout by the scan interval rather
than losing it. That is the whole reason Kafka is a latency optimisation in this
design and not the source of truth: turn the broker off entirely and every
payout still settles, just later.

The alternative ordering is worse in the direction that matters. Publishing
before the commit would let the worker receive an event for a row that never
lands, and a consumer that cannot find its payout has nothing useful to do.
Losing an event only delays work the database still knows about.

A transactional outbox closes the gap properly: write the event to an
`outbox` table in the same transaction as the payout and let a relay publish it.
It is not here because it needs a relay, a table and its own failure modes, and
because the claim scan already bounds the loss. Named here rather than left for
a reviewer to find.

`PayoutWorkerIT` pins this: a row nothing ever published an event for is picked
up by the scan, which is exactly the shape of the failure above.

### Settlement is simulated

No money moves anywhere in this repository, and the interesting question is what
takes its place. A gateway that always succeeds would leave the retry policy,
the error taxonomy and the dead letter topic as prose. So the simulated provider
fails **deterministically, on the amount**, which is the one field a caller
controls freely and the API already validates:

| Request | Provider answers | What the pipeline does |
| --- | --- | --- |
| amount ending in `.13` | transient failure | retried after 1 min, 5 min, then 30 min, until the attempt budget runs out, then `FAILED` + dead letter `exhausted` |
| amount ending in `.66` | permanent rejection | `FAILED` and dead lettered `permanent` immediately, with no retry |
| currency `XTS` | permanent rejection | the same, reachable without picking a special amount |
| anything else | success | `CONFIRMED` after `payout.worker.settlement.latency` |

`XTS` is the code ISO 4217 reserves for testing, so it passes the API's currency
validation and fails at settlement rather than at the edge. All four rows are
configurable under `payout.worker.settlement.*`, so none of this is baked in.

Deterministic rather than random on purpose. A gateway that fails one call in
ten produces a test suite that passes most of the time, which is worse than no
suite: nobody trusts it and everybody reruns it.

### What the worker guarantees, and what it does not

- **A redelivered event settles nothing twice.** The claim is one conditional
  `UPDATE`; the second delivery matches no row and stops.
- **A crash mid-settlement costs an attempt.** The attempt is counted when the
  row is claimed, not when a result comes back, so a worker that dies after
  calling a provider does not get the attempt back. That is the conservative
  direction: the alternative is retrying a payment that may already have gone
  through.
- **A crashed worker's payouts are not stranded.** A `PROCESSING` row whose lock
  is older than `payout.worker.stale-lock` is reclaimed by the scan.
- **Failure is bounded.** Transient failures get `payout.worker.max-attempts`
  attempts; permanent ones get one. Everything that will never settle ends on
  `payout.requested.dlt` with `X-Failure-Kind` saying which of the two it was.
- **What it does not guarantee: that a dead letter was published.** The row is
  marked `FAILED` first, so a broker outage between the two costs the alert and
  not the outcome. Same gap as the missing outbox, bounded the same way, and in
  [Known limitations](#known-limitations) rather than left to be discovered.

## What "exactly once" actually means here

Worth stating precisely, because the phrase is usually sold without its
conditions and this pipeline exists to demonstrate the conditions.

**What the pipeline guarantees on its own.** A payout is settled at most once
per claim. Two workers handed the same event both run the conditional `UPDATE`;
the database serialises them and the loser updates zero rows. Every state
change afterwards is guarded by the `lock_token` that claim minted, so a worker
whose lock was reclaimed while it was working updates nothing instead of writing
over the worker that holds the row now. Immediately before the provider is
called, the lock is renewed: a renewal that updates nothing is this worker being
told the row is no longer its own while that still costs nothing.

**What it cannot guarantee, and no amount of care upstream would.** The worker
can die in the window between the provider accepting the money and the row
recording that it did. The row is then `PROCESSING` with a lock nobody will
release, the stale-lock timeout expires, another worker claims it, and the
provider is called a second time. That is deliberate. The alternative is to drop
payouts whose outcome is merely unknown, and for money, retrying an unknown is
the safer direction.

**So the second call has to be harmless, and that is the provider's job.**
`SettlementInstruction` carries the payout id as the idempotency key, and
`SettlementGateway` documents deduplicating on it as part of the contract rather
than as a suggestion. `SimulatedSettlementGateway` honours it, so the simulation
demonstrates the guarantee instead of quietly contradicting it.

**Where it is still thin.** The simulated gateway remembers settled ids in
process memory, so a restart forgets them. A real provider remembers across
restarts; until there is one, that difference is a property of the stand-in and
is named here rather than left to be discovered. There is also no timeout on the
settlement call itself: a provider that hangs forever holds a worker thread and
its lock is renewed only once, before the call, so the row does eventually
become reclaimable. Bounding that call is day 4 work.

## Honest scope

What works today, and is covered by tests:

| Capability | State |
| --- | --- |
| `POST /payouts`, `GET /payouts/{id}` | Implemented |
| Bean Validation, including a real ISO 4217 currency check | Implemented |
| RFC 7807 problem responses on every failure path | Implemented |
| Flyway migration, PostgreSQL, `ddl-auto: validate` | Implemented |
| Idempotency by `Idempotency-Key` header, enforced by a partial unique index | Implemented |
| Correlation id: accepted, sanitised, persisted, echoed, put in the MDC | Implemented |
| `payout.requested` published on create, keyed by payout id, correlation id in a record header | Implemented |
| The worker consumes it with manual acknowledgement, and survives a poison payload | Implemented |
| An `Idempotency-Key` reused with a different body is `422`, not the first payout | Implemented |
| Atomic claim: one conditional `UPDATE`, so a redelivered event settles nothing twice | Implemented |
| Fenced transitions: every state change is guarded by the lock token its claim minted | Implemented |
| The lock is renewed immediately before settling, so a batch tail cannot go stale unnoticed | Implemented |
| Settlement through a gateway with a transient versus permanent error taxonomy | Implemented, **simulated** |
| Retry with a fixed backoff ladder and a bounded attempt budget | Implemented |
| Dead letter topic `payout.requested.dlt`, with the failure diagnosed in headers | Implemented |
| Claim scan: due retries, stale locks, and rows whose event was never published | Implemented |
| Integration tests against real PostgreSQL via Testcontainers, and against an embedded Kafka broker | Implemented |

What is **not** implemented yet, despite being described above:

| Missing | Arrives on |
| --- | --- |
| **Real settlement.** The gateway is simulated and moves no money | Not planned; this is a portfolio |
| A consumer or replay tool for `payout.requested.dlt`. Records land there and nothing drains them | Day 4 or later |
| Transactional outbox. A publish that fails leaves a `PENDING` row and no event | Not planned; the claim scan bounds it |
| Prometheus metrics, JSON logs | Day 4 |
| LLM backed endpoint | Day 4 |
| AWS deployment design | Day 5 |

Other things this deliberately does not do, and will not by day 5:

- **It is not deployed to AWS.** Day 5 produces a written deployment design, not
  a running environment. It will be labelled as such.
- **There is no authentication.** Anyone who can reach the port can create a
  payout. A real payout API needs mTLS or OAuth2 at minimum.
- **No money moves.** Settlement is a simulated call with injectable failure
  modes, which is what makes the retry and dead letter paths demonstrable. A
  `CONFIRMED` payout in this repository means the pipeline believes it settled
  one, not that anybody was paid.
- **`POST /payouts` returning 201 does not mean the payout happened.** It means
  the request is durable and queued.

## Tech stack

| | |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Persistence | PostgreSQL 16; Spring Data JPA and Flyway in the API, `JdbcClient` in the worker |
| Messaging | Apache Kafka 3.9 in KRaft mode, Spring for Apache Kafka |
| Build | Maven multi module |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers, embedded Kafka |

## Running it

Prerequisites: JDK 21, Maven 3.9+, Docker (for the database, the broker and the
integration tests).

```bash
cp .env.example .env          # every value may stay blank for local defaults
docker compose up -d          # PostgreSQL on 5432, Kafka on 9092, kafka-ui on 8090
mvn -pl payout-api spring-boot:run       # migrates the schema, then serves HTTP
mvn -pl payout-worker spring-boot:run    # in a second shell, once the API has migrated
```

The API comes up on `http://localhost:8080` and the worker on
`http://localhost:8081`. **The API migrates the schema; the worker never does.**
Flyway runs on the API's first start and Hibernate refuses to boot if the
entities and the migration disagree, so a worker started against an unmigrated
database simply fails its statements until the API has been up. The broker
auto-creates `payout.requested` and `payout.requested.dlt` on the first publish;
`http://localhost:8090` shows both topics, their partitions and the worker's
consumer group lag.

The API starts and serves requests whether or not the broker is up. Without one,
`POST /payouts` still answers 201 and logs the failed publish at ERROR; see
[There is no outbox](#there-is-no-outbox-and-here-is-what-that-costs).

Create a payout:

The `Idempotency-Key` is the caller's secret. Anyone who knows it and the body
can read the payout back, and a different body confirms the key exists (422).
Use an unguessable value, a UUID as below, never an order number: there is no
authentication on this intake, so the key is the only thing standing between a
payout and a stranger.

```bash
curl -i -X POST http://localhost:8080/payouts \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f3e9c2a-5b1d-4e8f-9a6c-2d4b8e1f0a37' \
  -H 'X-Correlation-Id: demo-trace-1' \
  -d '{"amount": 125.50, "currency": "BRL"}'
```

```
HTTP/1.1 201 Created
Location: /payouts/9f1c...e3
X-Correlation-Id: demo-trace-1

{"id":"9f1c...e3","amount":125.5000,"currency":"BRL","status":"PENDING",
 "correlationId":"demo-trace-1","attempts":0,"lastError":null,"nextAttemptAt":null,
 "createdAt":"2026-07-27T10:15:30Z","updatedAt":"2026-07-27T10:15:30Z"}
```

The worker picks it up a moment later, under the correlation id the request came
in with, and `GET /payouts/9f1c...e3` is `CONFIRMED` by the time you can type it:

```
2026-07-27T10:15:30.412Z INFO  [demo-trace-1] i.g.o.p.w.p.PayoutRequestedListener
  - Received payout.requested for payout 9f1c...e3
2026-07-27T10:15:30.418Z INFO  [demo-trace-1] i.g.o.p.w.p.PayoutProcessor
  - Settling payout 9f1c...e3 for 125.5000 BRL, attempt 1 of 5
2026-07-27T10:15:30.630Z INFO  [demo-trace-1] i.g.o.p.w.p.PayoutProcessor
  - Confirmed payout 9f1c...e3 on attempt 1
```

Run the exact same command again. You get **200 OK**, the same `id`, there is
still only one row, and the worker settles nothing: a replay publishes no second
event, and even if one arrived the claim would find a `CONFIRMED` row and stop.
That is the whole thesis in one retry.

Reuse that key with a **different** body and you get **422**, not the first
payout:

```bash
curl -i -X POST http://localhost:8080/payouts \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 7f3e9c2a-5b1d-4e8f-9a6c-2d4b8e1f0a37' \
  -d '{"amount": 999.99, "currency": "BRL"}'
```

### Watching a payout fail

A transient failure, retried on the backoff ladder. Ends in `.13`:

```bash
curl -s -X POST http://localhost:8080/payouts \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: demo-transient' \
  -d '{"amount": 42.13, "currency": "USD"}'
```

```
WARN  [demo-transient] i.g.o.p.w.p.PayoutProcessor - Payout 3b0a...91 failed
  transiently on attempt 1 of 5, next attempt at 2026-07-27T10:16:30Z:
  SettlementUnavailableException: Settlement provider is unavailable, try again later (simulated for .13)
```

`GET /payouts/3b0a...91` now shows `PENDING`, `attempts: 1` and a
`nextAttemptAt` a minute out. The claim scan picks it up when it comes due,
tries again, and after the fifth attempt the payout is `FAILED` and a record
lands on `payout.requested.dlt` with `X-Failure-Kind: exhausted`. On the default
ladder that is 1 + 5 + 30 + 30 minutes of waiting, so set
`PAYOUT_WORKER_MAX_ATTEMPTS=2` if you would rather watch it happen inside a
coffee break.

A permanent failure, dead lettered on the first attempt. Ends in `.66`:

```bash
curl -s -X POST http://localhost:8080/payouts \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: demo-permanent' \
  -d '{"amount": 42.66, "currency": "USD"}'
```

```
WARN  [demo-permanent] i.g.o.p.w.p.PayoutProcessor - Payout 7c41...02 failed
  permanently on attempt 1: SettlementRejectedException: Settlement was rejected
  by the provider (simulated for .66)
WARN  [demo-permanent] i.g.o.p.w.p.PayoutDeadLetterPublisher - Dead lettered
  payout 7c41...02 to payout.requested.dlt after 1 attempt(s)
```

`http://localhost:8090` shows the record on `payout.requested.dlt`: the original
event as the body, and `X-Failure-Kind: permanent` with `X-Failure-Reason`,
`X-Attempts` and `X-Correlation-Id` beside it. Nothing consumes that topic yet.

To watch the claim scan instead of the Kafka path, stop the broker
(`docker compose stop kafka`) and create a payout. The API still answers 201 and
logs the failed publish at ERROR; the worker settles the payout within one
`PAYOUT_WORKER_POLL_INTERVAL` anyway, because it reads the table.

### Worker configuration

Every value has a local default, is bound to a typed record at startup, and is
overridable by environment variable. All of them are in `.env.example`.

| Property | Env | Default | What it decides |
| --- | --- | --- | --- |
| `payout.worker.max-attempts` | `PAYOUT_WORKER_MAX_ATTEMPTS` | `5` | Attempts before a payout is failed and dead lettered |
| `payout.worker.stale-lock` | `PAYOUT_WORKER_STALE_LOCK` | `5m` | How long a claimed row may stay locked before another worker may take it |
| `payout.worker.poll-interval` | `PAYOUT_WORKER_POLL_INTERVAL` | `15s` | How often the claim scan runs |
| `payout.worker.poll-batch-size` | `PAYOUT_WORKER_POLL_BATCH_SIZE` | `50` | Rows one scan claims |
| `payout.worker.poll-enabled` | `PAYOUT_WORKER_POLL_ENABLED` | `true` | Whether the scan exists at all |
| `payout.worker.settlement.*` | `PAYOUT_WORKER_SETTLEMENT_*` | see above | Latency and the simulated failure triggers |

The worker reads the same `PAYOUT_DB_*` variables as the API. It has no Flyway
and no JPA: the API migrates, the worker claims.

## API

### `POST /payouts`

| | |
| --- | --- |
| Body | `{"amount": <decimal>, "currency": "<ISO 4217>"}` |
| `Idempotency-Key` header | Optional, up to 128 characters. Makes the call safe to retry, **with the same body** |
| `X-Correlation-Id` header | Optional. Reused if it matches `[A-Za-z0-9_-]{1,64}`, replaced otherwise |
| `201 Created` | A new payout was accepted |
| `200 OK` | This idempotency key had already created a payout. Nothing new happened |
| `400 Bad Request` | Validation or parse failure |
| `422 Unprocessable Entity` | This idempotency key was already used for a *different* request |

`amount` must be greater than zero with at most 15 integer digits and 4 decimal
places, matching the `numeric(19,4)` column. `currency` must be an upper case
code the JDK recognises, so `XYZ` and `brl` are both rejected.

**What an `Idempotency-Key` promises, and what happens when a caller breaks
it.** The key says "this is the same request again". The row stores a SHA-256 of
the canonical request behind it, the amount at scale 4 and the currency, and a
replay is compared against it:

- same key, same request: `200 OK` with the original payout, nothing created,
  no second event. `250.00` and `250` are the same request; the amount is
  normalised before it is hashed.
- same key, different amount or currency: `422` with type
  `urn:payout:error:idempotency-mismatch`. Not `409`, because a retry can never
  fix it: the same key with the same new body fails identically forever.
  Changing one of the two is the only way out.
- neither the key nor the existing payout's id is echoed back. The caller knows
  its own key, and the id belongs to the earlier request.

Rows written before the fingerprint column existed have none, and unknown is not
the same as different: those replay as they always did.

### `GET /payouts/{id}`

`200 OK` with the payout, or `404 Not Found`.

### Errors

Every failure is `application/problem+json` per RFC 7807, with two extension
members on all of them: `correlationId` and `timestamp`.

```json
{
  "type": "urn:payout:error:validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request body failed validation. See the errors field.",
  "instance": "/payouts",
  "correlationId": "demo-trace-1",
  "timestamp": "2026-07-27T10:15:30Z",
  "errors": [
    { "field": "currency", "message": "must be a valid ISO 4217 currency code, upper case" }
  ]
}
```

| `type` | Status | Meaning |
| --- | --- | --- |
| `urn:payout:error:validation-failed` | 400 | A field or parameter failed validation. See `errors` |
| `urn:payout:error:malformed-request` | 400 | Body is missing, not JSON, or a value would not bind |
| `urn:payout:error:payout-not-found` | 404 | No payout with that id |
| `urn:payout:error:conflict` | 409 | The request collided with existing state |
| `urn:payout:error:idempotency-mismatch` | 422 | The `Idempotency-Key` was already used for a different request |
| `urn:payout:error:internal-error` | 500 | A bug. Details are logged against the correlation id, never returned |

The `type` URI is the stable contract. `title` and `detail` are for humans and
may be reworded.

## Data model

```sql
CREATE TABLE payouts (
    id                      uuid           PRIMARY KEY,
    idempotency_key         varchar(128),
    idempotency_fingerprint varchar(64),           -- V2: SHA-256 of the canonical request
    amount                  numeric(19,4)  NOT NULL,
    currency                varchar(3)     NOT NULL,
    status                  varchar(16)    NOT NULL,   -- PENDING | PROCESSING | CONFIRMED | FAILED
    correlation_id          varchar(64)    NOT NULL,
    attempts                integer        NOT NULL DEFAULT 0,
    last_error              varchar(2048),
    next_attempt_at         timestamptz,           -- V2: when a retry is due. NULL = now
    locked_at               timestamptz,           -- V2: who holds the row. NULL = nobody
    created_at              timestamptz    NOT NULL,
    updated_at              timestamptz    NOT NULL,

    -- V2: a lock only means something on a row being processed
    CONSTRAINT ck_payouts_locked_only_while_processing
        CHECK (locked_at IS NULL OR status = 'PROCESSING')
);

CREATE UNIQUE INDEX ux_payouts_idempotency_key
    ON payouts (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- V2: the claim scan reads this in order and stops at the batch limit
CREATE INDEX ix_payouts_claimable
    ON payouts (created_at) WHERE status IN ('PENDING', 'PROCESSING');
```

This table is also the work queue, and the claim is the whole concurrency
design:

```sql
UPDATE payouts
   SET status = 'PROCESSING', attempts = attempts + 1, locked_at = ?, updated_at = ?
 WHERE id = ?
   AND (    (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
         OR (status = 'PROCESSING' AND locked_at < ?) )   -- the stale lock arm
RETURNING id, amount, currency, correlation_id, attempts, created_at;
```

Two workers handed the same event both run it; PostgreSQL serialises them and
the loser updates zero rows, gets an empty result and stops. No lock is held
across the settlement call, so nothing can time out mid-payment, and a
redelivered Kafka event is a no op instead of a second payment. `RETURNING`
makes the claim and the read one round trip, which closes the window in which
the row could change between taking it and looking at it.

V2 replaced V1's `ix_payouts_unsettled`, which led on `status`. The scan wants
the oldest claimable row across *both* claimable statuses, and a leading status
column made that a sort. `created_at` alone, with the statuses in the predicate,
makes it an ordered index scan.

## Design decisions

The reasoning behind each choice is written in the code, next to the code it
explains. The eight that matter most:

1. **Idempotency is a database constraint, not an `if` statement.** The service
   does look the key up first, but only as a fast path. Correctness comes from
   `ux_payouts_idempotency_key`: concurrent requests race into the index, one
   insert wins, and the loser catches the violation and is handed the winner's
   row. A check-then-insert has a window between the two; an index does not.
   `PayoutApiIT` fires twelve simultaneous identical requests and asserts that
   exactly one row exists.

2. **`PayoutService.create` is deliberately not `@Transactional`.** The recovery
   path reads the database after catching a constraint violation, and inside an
   active transaction that read fails, because the transaction is already marked
   rollback only. Leaving each repository call to its own transaction is what
   makes the recovery possible.

3. **`ddl-auto: validate`, with Flyway owning the schema.** `update` lets a
   mapping change rewrite production DDL in an order nobody reviewed. `validate`
   inverts it: the migration is the truth, and the application refuses to start
   if the entities have drifted from it.

4. **Testcontainers, not H2.** The design depends on partial unique indexes and
   on how PostgreSQL behaves when two transactions collide on one. H2 reproduces
   neither. A test that passes on a database you do not deploy is a test that
   lies.

5. **`Clock` is injected.** `Instant.now()` inside a service can only ever be
   asserted as "roughly now", which is how tests get flaky. It is also what makes
   the backoff assertable: the claim statements take the instant as a parameter
   rather than calling SQL's `now()`, so a fixed clock puts an exact
   `next_attempt_at` in the row and the test asserts that instant, not a window
   around it, and nothing has to sleep.

6. **The Kafka key is the payout id, and the acknowledgement is manual.** The
   key is what puts every event about one payout on one partition, in order, in
   front of one consumer. It is not what stops two workers settling the same
   payout, and it is worth being exact about that: the claim scan reaches rows
   without going through Kafka at all, and the stale-lock arm of the claim
   exists precisely to let a second worker take a row the first one is holding.
   What keeps two workers off one payout is the conditional `UPDATE` and the
   lock token it mints, not the partition.
   The manual acknowledgement is what makes the committed offset mean "the
   listener finished" rather than "a timer fired": with auto-commit, a worker
   that dies mid-settlement comes back to a payout Kafka believes was handled.
   The listener acknowledges on every path, failures included: an unacknowledged
   record comes straight back, fails the same way and stops the consumer making
   progress on everything else, while the row it failed on is still in the table
   for the scan to find.

7. **The attempt is counted at claim time, not when a result comes back.** So a
   worker that dies mid-settlement has already spent an attempt, and a payout
   that crashes five workers is dead lettered rather than retried forever. The
   alternative counts only completed attempts, which sounds fairer and means a
   settlement that may already have reached a provider is retried for free. For
   money the conservative direction is the one that risks a payout arriving late
   over one arriving twice. The stale-lock recovery carries the same rule: a row
   reclaimed with its budget already spent is failed *without* being settled
   again.

8. **The worker never touches the API's JPA entity.** It reaches `payouts`
   through one repository of hand written statements, and there is no
   `spring-boot-starter-data-jpa` on its classpath at all, so the boundary is a
   fact of the build rather than a rule someone has to remember. It is also the
   right tool: what makes the claim correct is the exact `WHERE` clause, and an
   ORM's optimistic locking would throw where this needs a silent zero-row
   update. Flyway is absent from that classpath for the same reason, at runtime:
   the API owns the schema and migrates it on start.

A full study guide, with the rejected alternative for every decision, lands with
day 5.

## Testing

```bash
mvn test      # unit, web slice and embedded Kafka tests, no Docker needed
mvn verify    # the above, plus Testcontainers integration tests
```

121 unit, slice and embedded-Kafka tests run without Docker; 36 integration
tests need it. `mvn verify` starts real PostgreSQL 16 containers, one for the
API's tests and one for the worker's. Without Docker the integration tests are
skipped rather than failed, so `mvn test` is always runnable.

Kafka is different: the producer and the consumer tests run an in-JVM KRaft
broker through `spring-kafka-test`, so they run under `mvn test` on a machine
with no Docker at all. There is nothing about a broker in a container those
tests need. The database is the part that has to be the real thing.

The split is intentional. Unit tests cover the decisions a service makes,
including the ones only reachable by simulating a lost race, by making the
broker throw, or by claiming a payout whose attempt budget is already spent.
Integration tests cover the claims only real infrastructure can confirm: that
the migrations apply, that the indexes are partial, that the check constraints
reject bad rows written behind the API's back, that concurrent requests collapse
into one payout, that a record published by the API deserialises into the same
event on the consumer's side, that twelve simultaneous claims of one
payout produce exactly one winner, and that the scan skips a row another
transaction is holding rather than blocking on it.

`PayoutWorkerIT` applies the API's own migration files to its container rather
than a copy of the DDL. A claim tested against a schema nobody deploys is a
claim tested against nothing.

The worker's Kafka tests swap PostgreSQL for a map and keep everything else:
real listener, real claim logic, real settlement, real dead letter. That proves
the pipeline without Docker and proves nothing about the SQL, which is exactly
the division of labour intended.

### CI

GitHub Actions runs `./mvnw -B verify` on every push and pull request (see
`.github/workflows/ci.yml`). The runner has Docker available, so the
Testcontainers integration tests run there rather than being skipped.

## Layout

```
payout-platform/
├── pom.xml                  aggregator: Spring Boot BOM, build plugins
├── docker-compose.yml       PostgreSQL, Kafka, kafka-ui
├── payout-contracts/        the wire contract, and nothing else
│   └── src/main/java/io/github/orlandol23/payout/contracts/
│       ├── PayoutRequested  the event record, on both topics
│       └── PayoutTopics     payout.requested and payout.requested.dlt
├── payout-api/              HTTP edge
│   └── src/main/java/io/github/orlandol23/payout/api/
│       ├── config/          Clock bean
│       ├── correlation/     correlation id filter and provider
│       ├── error/           RFC 7807 handler and problem types
│       ├── payout/          entity, repository, service, idempotency fingerprint
│       │   └── events/      the payout.requested producer
│       └── validation/      ISO 4217 constraint
└── payout-worker/           consumer and settler
    └── src/main/java/io/github/orlandol23/payout/worker/
        ├── config/          Clock bean, payout.worker.* settings
        ├── correlation/     correlation id read off the record header
        ├── payout/          the listener, the claim statements, the processor,
        │                    the backoff ladder, the claim scan, the dead letter
        └── settlement/      the gateway, the simulated provider, the taxonomy
```

### Why one repository and not two

Two Spring Boot services could live in two repositories, and in a company with
two teams and independent release trains they probably should: separate
repositories keep deploy cadences from being coupled to each other.

Here they are one Maven multi module build, for reasons specific to this being a
portfolio and not a product:

- A reviewer clones one thing and sees the whole system. Two repositories make
  them find the second half and join it up mentally.
- The producer and the consumer share a wire contract. In one build that is the
  `payout-contracts` module and the compiler catches drift: rename a field on
  `PayoutRequested` and both services fail to compile in the same build. Across
  two repositories it needs a published artifact, or it gets copy pasted and
  drifts until a consumer silently reads null.
- One CI pipeline, one compose file, one README.

The cost is real and worth naming: a shared module invites the coupling that
microservices exist to avoid. The mitigation is a rule, that `payout-contracts`
holds the event records and the topic names and nothing else. No business logic,
no JPA entities, no Spring. It has no compile scope dependencies at all, which is
the cheapest way to keep it that way: anything worth putting there needs no
library, and anything that needs a library does not belong there.

The rule has already been paid for once. The worker needs the same correlation id
validation the API has, and it got its own copy rather than a shared one, because
two constants and a regular expression are not a wire contract.

### Why the two services share a database

Normally an anti pattern. Here it is the mechanism. The queue is a table, and the
claim is a conditional update against it, so both services need it by
construction. Splitting them would mean the API calls the worker to enqueue,
which reintroduces exactly the synchronous coupling the design removes.

The boundary is kept narrow instead: the worker touches `payouts` through five
hand written statements and never through the API's JPA entity, which is
enforced by there being no JPA on its classpath.

## Known limitations

Named here rather than discovered in review:

- **Random UUID primary keys.** `uuid_generate_v4` style keys are scattered
  across the B-tree, so inserts touch random pages and the index fragments. A
  time ordered key, UUIDv7 or ULID, would keep inserts appending. Worth changing
  under real write volume; not worth the extra dependency at this size.
- **`last_error` is capped at 2048 characters.** Enough for a message, not for a
  stack trace, and the worker truncates deliberately rather than letting a stack
  trace define the row size. The full trace goes to the log under the
  correlation id, where it can be read whole and expires on the log's retention.
- **Nothing consumes `payout.requested.dlt`.** Failures land there with the
  original event and a diagnosis in headers, and then they sit. A replay tool
  that puts a corrected payout back on `payout.requested` is the obvious next
  thing and is not written; today the topic is somewhere to look, not somewhere
  work gets done.
- **A dead letter that fails to publish is only a log line.** The row is marked
  `FAILED` first, on purpose, so a broker outage between the two costs the alert
  rather than leaving a stale lock that gets settled again. The same gap as the
  missing outbox, and bounded the same way: `GET /payouts/{id}` still tells the
  truth.
- **The claim scan is one instance's whole table.** `FOR UPDATE SKIP LOCKED`
  means several workers can sweep at once without fighting, but every one of
  them scans from the oldest row each interval. That is right at this size and
  wrong at a few million rows, where the scan wants to be keyset paginated or
  partitioned by worker.
- **No pagination or list endpoint.** `GET /payouts/{id}` only, because nothing
  in the pipeline needs a list yet.
- **Idempotency keys are never expired.** In production this table needs a
  retention policy, or it grows forever, and the fingerprint makes the question
  sharper rather than softer: a key that is kept forever means a body that can
  never be reused, and a key that is dropped means a replay that quietly creates
  a second payout. Which of the two, and after how long, is an open question
  here.
- **The local broker is one node with no replication.** The producer asks for
  `acks=all`, which on a single broker cluster means "one broker has it". That is
  not the durability the setting suggests. A real deployment runs three brokers
  with `min.insync.replicas=2`, and only then does `acks=all` mean what it reads
  like. The setting is here now because it is the one that has to be right before
  there is a cluster to make it true.
- **Topics are auto-created.** Convenient on a laptop, wrong in production, where
  a typo in a topic name should fail loudly instead of quietly creating a topic
  nobody consumes. Partition counts and retention belong in provisioning, not in
  a broker default.
- **A lost insert race logs at ERROR.** Hibernate's `SqlExceptionHelper` writes
  the constraint violation before Spring translates it and the service recovers,
  so a perfectly handled collision still looks alarming in the log. Turning that
  logger down would also hide real SQL failures, so it stays. Losing the race
  requires genuine concurrency on one key, so the noise is proportional to how
  often that actually happens.

## Roadmap

Beyond Day 5: [`docs/ROADMAP.md`](docs/ROADMAP.md). The September 2026 audit,
with the status of every finding: [`docs/AUDIT-2026-09.md`](docs/AUDIT-2026-09.md).

- [x] **Day 1** API, PostgreSQL, Flyway, validation, RFC 7807, idempotency
- [x] **Day 2** Kafka producer and consumer, keyed by payout id, manual commit
- [x] **Day 3** atomic claim, error taxonomy, backoff, dead letter topic
- [ ] **Day 4** Actuator, Micrometer, Prometheus, JSON logs, LLM endpoint
- [ ] **Day 5** AWS deployment design, study guide (CI and the licence are done)

## Licence

MIT, see [LICENSE](LICENSE).
