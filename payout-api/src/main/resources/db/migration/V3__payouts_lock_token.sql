-- Day 3 gave the worker a lock. It did not give it a way to prove the lock is
-- still its own.
--
-- confirm, scheduleRetry and fail all guard on `status = 'PROCESSING'`, which a
-- row still is after a second worker reclaimed its stale lock. So a worker
-- returning from a long settlement writes over the state of the worker that now
-- holds the row. Concretely: A claims P and calls the provider; the call runs
-- past the stale-lock window; B reclaims P and calls the provider a second time;
-- A comes back first with a transient failure and pushes P to PENDING, which the
-- guard allows because P is still PROCESSING; B then succeeds and its CONFIRMED
-- updates zero rows and is discarded in silence. P is now claimable again with
-- the money already sent twice.
--
-- lock_token is regenerated on every claim, so "this row is PROCESSING" and
-- "this claim still holds this row" stop being the same question. It does not
-- by itself stop the provider being called twice: that needs the settlement to
-- carry an idempotency key, and the lock not to expire while its holder is
-- still working. All three are needed together, and all three land with this
-- migration.

ALTER TABLE payouts
    ADD COLUMN lock_token uuid;

COMMENT ON COLUMN payouts.lock_token IS
    'Identifies the claim currently holding this row. Regenerated on every claim, so a superseded worker can be told apart from the one that holds the row now. NULL when nobody holds it';

-- Rows mid-flight when this migration runs hold a lock with no token. Giving
-- them one keeps the constraint below true from the first moment. It costs those
-- rows nothing: the worker holding one will find its next transition rejected,
-- release nothing, and the row is reclaimed on the stale timeout exactly as it
-- would have been if that worker had died.
UPDATE payouts
   SET lock_token = gen_random_uuid()
 WHERE locked_at IS NOT NULL;

-- The token and the timestamp are two halves of one lock: a claim sets both, and
-- confirm, fail and scheduleRetry clear both. Setting one without the other is a
-- bug in the worker's state machine, and the database is the cheapest place to
-- find out.
--
-- A plain CHECK rather than NOT VALID plus a later VALIDATE. Flyway runs this
-- migration in one transaction, so the ACCESS EXCLUSIVE lock taken by ADD
-- CONSTRAINT is held until commit either way and splitting it buys nothing here.
-- Splitting is worth it when the validation scan is long enough to matter and
-- can run in its own transaction; this column was NULL a moment ago and the
-- backfill above is the only thing the scan has to look at.
ALTER TABLE payouts
    ADD CONSTRAINT ck_payouts_lock_token_with_locked_at
        CHECK ((lock_token IS NULL) = (locked_at IS NULL));
