-- Day 3 turns the payouts table into a queue a worker can actually claim from.
-- V1 already carried the status and the attempt counter. What was missing is
-- when a retry becomes due, who is holding a row right now, and how to tell a
-- replayed idempotency key from a reused one.

ALTER TABLE payouts
    ADD COLUMN next_attempt_at         timestamptz,
    ADD COLUMN locked_at               timestamptz,
    ADD COLUMN idempotency_fingerprint varchar(64);

COMMENT ON COLUMN payouts.next_attempt_at IS
    'When a retry becomes due. NULL means claimable now';
COMMENT ON COLUMN payouts.locked_at IS
    'When the worker holding this row claimed it. NULL when nobody holds it';
COMMENT ON COLUMN payouts.idempotency_fingerprint IS
    'SHA-256 of the canonical request behind this key. NULL on rows created before V2';

-- A lock only means something on a row that is being processed. Anything else
-- holding one is a bug in the worker's state machine, and the database is the
-- cheapest place to find out. The claim sets both together, and confirm, fail
-- and scheduleRetry all clear the lock as they leave PROCESSING.
ALTER TABLE payouts
    ADD CONSTRAINT ck_payouts_locked_only_while_processing
        CHECK (locked_at IS NULL OR status = 'PROCESSING');

-- ix_payouts_unsettled was the right idea with the wrong column order, and day 3
-- is where that shows. The claim scan asks for "the oldest claimable row",
-- across both PENDING and PROCESSING, because a lock older than the timeout is
-- reclaimable. Leading on status meant PostgreSQL could use the index for the
-- filter but still had to sort two status groups to order them by created_at.
--
-- Moving status into the predicate and leaving created_at as the only key column
-- makes the scan an ordered index scan that stops at the batch limit. Partial for
-- the same reason as before: CONFIRMED and FAILED rows are never claimed and
-- would grow the index forever while the working set stays small.
DROP INDEX ix_payouts_unsettled;

CREATE INDEX ix_payouts_claimable
    ON payouts (created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
