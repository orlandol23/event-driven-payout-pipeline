# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Commit and PR conventions

Commits are authored as `Orlando Fernandes
<27815856+orlandol23@users.noreply.github.com>`, set by `env` in
`.claude/settings.json`. Confirm it landed with
`git log -1 --format='%an <%ae>'`; if another identity got in, amend with
`--reset-author` instead of leaving it in the history.

Nothing in a commit message or a pull request body may name the tool or the
session that wrote it: no `Co-Authored-By:` trailer, no `Claude-Session:`
trailer, no "Generated with/by Claude Code" footer, and no `claude.ai/code`
link. Describe the change, not how it was produced.

PR bodies may be written in Portuguese. They are read by the repository owner,
not by visitors browsing the code.

## Language

The README is the reference, and it is in English. So is everything else a
visitor reads on GitHub: documentation, code comments, test names, commit
messages and PR titles.

There is no locale bundle in this repository, so there is no product-content
exception.

## Layout

Maven multi-module, Java 21, Spring Boot 3.5.16:

| Module              | Responsibility                                          |
| ------------------- | ------------------------------------------------------- |
| `payout-contracts/` | Wire events and topic names. No logic, no framework.     |
| `payout-api/`       | HTTP intake, idempotency, publishes `payout.requested`.  |
| `payout-worker/`    | Consumes, claims, settles, retries, dead-letters.        |

`payout-contracts` stays free of framework code. It is the shared vocabulary
between the two services and nothing else, which is what lets them be deployed
and versioned separately.

## Commands

```bash
docker compose up -d                   # PostgreSQL 5432, Kafka 9092, kafka-ui 8090
./mvnw -B test                         # 116 tests, no Docker needed
./mvnw -B verify                       # the above plus 34 Testcontainers ITs
./mvnw -pl payout-api spring-boot:run  # migrates the schema, then serves HTTP
```

`test` covers unit, web slice and embedded Kafka. `verify` adds the
Testcontainers integration tests against real PostgreSQL and Kafka; with no
Docker daemon they are skipped by `@EnabledIf`. `Skipped: 34` in the failsafe
output means the daemon is missing, not that the tests passed. CI runs on
`ubuntu-latest`, which ships the daemon, so they run for real there.

Keep the test counts in `README.md` in sync when tests are added.

## Notes

- The worker claims a row with a conditional `UPDATE ... RETURNING` through
  `JdbcClient`, never through the API's JPA entity. Losing the race returns zero
  rows and the consumer exits clean, and that is precisely what makes a
  redelivered Kafka event a no-op. Do not collapse it into a read-then-write.
- Timestamps in that SQL come from the injected `Clock`, never from `now()`, so
  the retry backoff stays testable.
- The table is the source of truth; Kafka is a latency optimisation. The poller
  that sweeps due retries, stale locks and lost publishes is what makes that
  true. A change that assumes the event always arrives breaks the guarantee.
- `SettlementGateway`'s simulated implementation has deliberately documented
  failure modes: an amount ending in `.13` is transient, `.66` is permanent, and
  currency `XTS` is permanent. Reviewers use them to exercise every path with
  curl, so keep them working.
- An `Idempotency-Key` replayed with a different body is a 422, not a replay of
  the original. Rows written before migration V2 have a NULL fingerprint and
  still replay; that is documented rather than silently patched.
