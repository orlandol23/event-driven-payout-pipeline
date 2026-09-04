# payout-platform

![CI](https://github.com/orlandol23/event-driven-payout-pipeline/actions/workflows/ci.yml/badge.svg)

An asynchronous payout pipeline in Java 21 and Spring Boot 3: an HTTP edge that
accepts payout requests, and a worker that settles them exactly once.

> **Status: day 2 of 5.** The API, the schema, the idempotency guarantee and the
> Kafka hop between the two services are implemented and tested. Settlement,
> retries, observability and the AWS deployment notes are not. See
> [Honest scope](#honest-scope) for the precise line between the two.

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
|  payout-api   |  --- payout.requested --->   |  payout-worker  |
|  Spring Boot  |  Kafka, keyed by payout id   |   Spring Boot   |
+---------------+                              +-----------------+
       |                                                |
       |  INSERT status=PENDING          atomic claim, settle, retry
       |                                                |
       +--------------->  PostgreSQL  <-----------------+
                       partial unique index
                         = idempotency
```

The target flow, once all five days are in:

1. `POST /payouts` validates the request, writes it as `PENDING`, and publishes
   `payout.requested` keyed by payout id.
2. The worker consumes, claims the row atomically
   (`UPDATE ... WHERE status = 'PENDING'`), settles it, and moves it to
   `CONFIRMED` or `FAILED`.
3. A transient failure (network, timeout, 5xx) is retried with exponential
   backoff. A permanent failure (validation, 4xx) goes straight to the dead
   letter topic with no retry.
4. Redelivering the same event does no work twice.

Step 1 is implemented. The worker consumes the event, logs it and acknowledges
it; the claim, the settlement and the retry policy in steps 2 to 4 are not
written yet.

### There is no outbox, and here is what that costs

The insert commits, then the event is published. They are two operations, not
one, and nothing makes them atomic. If the broker is unreachable in the gap, the
row exists as `PENDING`, the event does not, and the request still answers
**201**.

That answer stays truthful, because 201 has never meant the money moved: it
means the request is durable and queued, and the row *is* the queue. The publish
failure is logged at ERROR with the correlation id, and nothing republishes it.

The bound on the damage is day 3's claim scan. The worker will also sweep
`payouts` for rows that have been `PENDING` too long, which means an event lost
here delays a payout by the scan interval rather than losing it. That is the
whole reason Kafka is described as a latency optimisation in this design and not
as the source of truth.

The alternative ordering is worse in the direction that matters. Publishing
before the commit would let the worker receive an event for a row that never
lands, and a consumer that cannot find its payout has nothing useful to do.
Losing an event only delays work the database still knows about.

A transactional outbox closes the gap properly: write the event to an
`outbox` table in the same transaction as the payout and let a relay publish it.
It is not here because it needs a relay, a table and its own failure modes, and
because the claim scan already bounds the loss. Named here rather than left for
a reviewer to find.

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
| Integration tests against real PostgreSQL via Testcontainers, and against an embedded Kafka broker | Implemented |

What is **not** implemented yet, despite being described above:

| Missing | Arrives on |
| --- | --- |
| `payout-worker` settlement. It consumes, logs and acknowledges; it changes no payout | Day 3 |
| Atomic claim, retry with backoff, error taxonomy, dead letter topic | Day 3 |
| Transactional outbox. A publish that fails leaves a `PENDING` row and no event | Not planned; day 3's claim scan bounds it |
| Prometheus metrics, JSON logs | Day 4 |
| LLM backed endpoint | Day 4 |
| AWS deployment design | Day 5 |

Other things this deliberately does not do, and will not by day 5:

- **It is not deployed to AWS.** Day 5 produces a written deployment design, not
  a running environment. It will be labelled as such.
- **There is no authentication.** Anyone who can reach the port can create a
  payout. A real payout API needs mTLS or OAuth2 at minimum.
- **No money moves.** Settlement will be a simulated call with injectable failure
  modes, which is what makes the retry and dead letter paths testable.
- **`POST /payouts` returning 201 does not mean the payout happened.** It means
  the request is durable and queued.

## Tech stack

| | |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Persistence | PostgreSQL 16, Spring Data JPA, Flyway |
| Messaging | Apache Kafka 3.9 in KRaft mode, Spring for Apache Kafka |
| Build | Maven multi module |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers, embedded Kafka |

## Running it

Prerequisites: JDK 21, Maven 3.9+, Docker (for the database, the broker and the
integration tests).

```bash
cp .env.example .env          # every value may stay blank for local defaults
docker compose up -d          # PostgreSQL on 5432, Kafka on 9092, kafka-ui on 8090
mvn -pl payout-api spring-boot:run
mvn -pl payout-worker spring-boot:run    # in a second shell
```

The API comes up on `http://localhost:8080` and the worker on
`http://localhost:8081`. Flyway creates the schema on first start, and Hibernate
refuses to boot if the entities and the migration disagree. The broker
auto-creates `payout.requested` on the first publish; `http://localhost:8090`
shows the topic, its partitions and the worker's consumer group lag.

The API starts and serves requests whether or not the broker is up. Without one,
`POST /payouts` still answers 201 and logs the failed publish at ERROR; see
[There is no outbox](#there-is-no-outbox-and-here-is-what-that-costs).

Create a payout:

```bash
curl -i -X POST http://localhost:8080/payouts \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-1001' \
  -H 'X-Correlation-Id: demo-trace-1' \
  -d '{"amount": 125.50, "currency": "BRL"}'
```

```
HTTP/1.1 201 Created
Location: /payouts/9f1c...e3
X-Correlation-Id: demo-trace-1

{"id":"9f1c...e3","amount":125.5000,"currency":"BRL","status":"PENDING",
 "correlationId":"demo-trace-1","attempts":0,"lastError":null,
 "createdAt":"2026-07-27T10:15:30Z","updatedAt":"2026-07-27T10:15:30Z"}
```

The worker's log picks it up a moment later, under the correlation id the request
came in with:

```
2026-07-27T10:15:30.412Z INFO  [demo-trace-1] i.g.o.p.w.p.PayoutRequestedListener
  - Received payout.requested for payout 9f1c...e3: 125.5000 BRL requested at 2026-07-27T10:15:30Z
```

Run the exact same command again. You get **200 OK**, the same `id`, there is
still only one row, and the worker logs nothing: a replay publishes no second
event. That is the whole thesis in one retry.

## API

### `POST /payouts`

| | |
| --- | --- |
| Body | `{"amount": <decimal>, "currency": "<ISO 4217>"}` |
| `Idempotency-Key` header | Optional, up to 128 characters. Makes the call safe to retry |
| `X-Correlation-Id` header | Optional. Reused if it matches `[A-Za-z0-9_-]{1,64}`, replaced otherwise |
| `201 Created` | A new payout was accepted |
| `200 OK` | This idempotency key had already created a payout. Nothing new happened |
| `400 Bad Request` | Validation or parse failure |

`amount` must be greater than zero with at most 15 integer digits and 4 decimal
places, matching the `numeric(19,4)` column. `currency` must be an upper case
code the JDK recognises, so `XYZ` and `brl` are both rejected.

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
| `urn:payout:error:internal-error` | 500 | A bug. Details are logged against the correlation id, never returned |

The `type` URI is the stable contract. `title` and `detail` are for humans and
may be reworded.

## Data model

```sql
CREATE TABLE payouts (
    id              uuid           PRIMARY KEY,
    idempotency_key varchar(128),
    amount          numeric(19,4)  NOT NULL,
    currency        varchar(3)     NOT NULL,
    status          varchar(16)    NOT NULL,   -- PENDING | PROCESSING | CONFIRMED | FAILED
    correlation_id  varchar(64)    NOT NULL,
    attempts        integer        NOT NULL DEFAULT 0,
    last_error      varchar(2048),
    created_at      timestamptz    NOT NULL,
    updated_at      timestamptz    NOT NULL
);

CREATE UNIQUE INDEX ux_payouts_idempotency_key
    ON payouts (idempotency_key) WHERE idempotency_key IS NOT NULL;
```

This table is also the work queue. The worker will claim from it with a
conditional update rather than a lock, which is what makes a redelivered Kafka
event a no op instead of a second payment.

## Design decisions

The reasoning behind each choice is written in the code, next to the code it
explains. The six that matter most:

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
   asserted as "roughly now", which is how tests get flaky. It also makes day 3's
   backoff testable without sleeping.

6. **The Kafka key is the payout id, and the acknowledgement is manual.** The
   key is what puts every event about one payout on one partition, in order, in
   front of one consumer, so two workers can never hold the same payout at once.
   The manual acknowledgement is what makes the committed offset mean "the
   listener finished" rather than "a timer fired": with auto-commit, a worker
   that dies mid-settlement comes back to a payout Kafka believes was handled.
   Neither matters much while the listener only logs. Both have to be right
   before day 3 puts money behind them.

A full study guide, with the rejected alternative for every decision, lands with
day 5.

## Testing

```bash
mvn test      # unit, web slice and embedded Kafka tests, no Docker needed
mvn verify    # the above, plus Testcontainers integration tests
```

`mvn verify` starts a real PostgreSQL 16 container. Without Docker the
integration tests are skipped rather than failed, so `mvn test` is always
runnable.

Kafka is different: the producer and the consumer tests run an in-JVM KRaft
broker through `spring-kafka-test`, so they run under `mvn test` on a machine
with no Docker at all. There is nothing about a broker in a container those
tests need. The database is the part that has to be the real thing.

The split is intentional. Unit tests cover the decisions a service makes,
including the ones only reachable by simulating a lost race, or by making the
broker throw. Integration tests cover the claims only real infrastructure can
confirm: that the migration applies, that the index is partial, that the check
constraints reject bad rows written behind the API's back, that concurrent
requests collapse into one payout, and that a record published by the API
deserialises into the same event on the consumer's side.

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
│       ├── PayoutRequested  the event record
│       └── PayoutTopics     topic names, including day 3's dead letter topic
├── payout-api/              HTTP edge
│   └── src/main/java/io/github/orlandol23/payout/api/
│       ├── config/          Clock bean
│       ├── correlation/     correlation id filter and provider
│       ├── error/           RFC 7807 handler and problem types
│       ├── payout/          entity, repository, service, web layer
│       │   └── events/      the payout.requested producer
│       └── validation/      ISO 4217 constraint
└── payout-worker/           consumer
    └── src/main/java/io/github/orlandol23/payout/worker/
        ├── correlation/     correlation id read off the record header
        └── payout/          the payout.requested listener
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

The boundary is kept narrow instead: from day 3, the worker touches `payouts`
through the claim statement only, never through the API's JPA entity.

## Known limitations

Named here rather than discovered in review:

- **Random UUID primary keys.** `uuid_generate_v4` style keys are scattered
  across the B-tree, so inserts touch random pages and the index fragments. A
  time ordered key, UUIDv7 or ULID, would keep inserts appending. Worth changing
  under real write volume; not worth the extra dependency at this size.
- **`last_error` is capped at 2048 characters.** Enough for a message, not for a
  stack trace. Day 3 truncates deliberately rather than letting a stack trace
  define the row size.
- **No pagination or list endpoint.** `GET /payouts/{id}` only, because nothing
  in the pipeline needs a list yet.
- **Idempotency keys are never expired.** In production this table needs a
  retention policy, or it grows forever.
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

- [x] **Day 1** API, PostgreSQL, Flyway, validation, RFC 7807, idempotency
- [x] **Day 2** Kafka producer and consumer, keyed by payout id, manual commit
- [ ] **Day 3** atomic claim, error taxonomy, backoff, dead letter topic
- [ ] **Day 4** Actuator, Micrometer, Prometheus, JSON logs, LLM endpoint
- [ ] **Day 5** AWS deployment design, study guide (CI and the licence are done)

## Licence

MIT, see [LICENSE](LICENSE).
