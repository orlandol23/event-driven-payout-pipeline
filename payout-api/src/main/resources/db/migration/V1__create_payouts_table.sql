-- Payout requests. This table is also the work queue the worker claims from,
-- so the constraints below are load bearing, not decoration.

CREATE TABLE payouts (
    id              uuid          NOT NULL,
    idempotency_key varchar(128),
    amount          numeric(19, 4) NOT NULL,
    currency        varchar(3)    NOT NULL,
    status          varchar(16)   NOT NULL,
    correlation_id  varchar(64)   NOT NULL,
    attempts        integer       NOT NULL DEFAULT 0,
    last_error      varchar(2048),
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,

    CONSTRAINT pk_payouts PRIMARY KEY (id),

    -- The application enum is the source of truth, but the database refuses to
    -- store a value outside it. Guards against a bad migration or a hand written
    -- UPDATE putting the row into a state no code can handle.
    CONSTRAINT ck_payouts_status CHECK (status IN ('PENDING', 'PROCESSING', 'CONFIRMED', 'FAILED')),

    -- Money invariants belong next to the money.
    CONSTRAINT ck_payouts_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payouts_attempts_non_negative CHECK (attempts >= 0),

    -- ISO 4217 alphabetic code, upper case only. Bean Validation rejects bad
    -- codes at the edge; this stops anything else from writing one.
    CONSTRAINT ck_payouts_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

-- Idempotency, enforced by the database rather than by a read-then-write check
-- in application code. Two concurrent requests carrying the same key race into
-- this index; exactly one insert wins and the loser is handed the winner's row.
--
-- Partial on purpose. The key is optional, and PostgreSQL treats NULLs as
-- distinct, so a plain unique index would still work but would carry one dead
-- entry per keyless payout. Excluding them keeps the index proportional to the
-- callers that actually asked for idempotency.
CREATE UNIQUE INDEX ux_payouts_idempotency_key
    ON payouts (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Supports the worker's claim scan on day 3: oldest unfinished payout first.
-- Partial again, because CONFIRMED and FAILED rows are never scanned and would
-- otherwise grow the index forever while the working set stays small.
CREATE INDEX ix_payouts_unsettled
    ON payouts (status, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

COMMENT ON TABLE payouts IS 'Payout requests and the queue the worker claims from';
COMMENT ON COLUMN payouts.idempotency_key IS 'Caller supplied key making POST /payouts safe to retry';
COMMENT ON COLUMN payouts.correlation_id IS 'Request identifier propagated through logs, and through Kafka headers from day 4';
COMMENT ON COLUMN payouts.attempts IS 'Number of settlement attempts made by the worker';
