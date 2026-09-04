package io.github.orlandol23.payout.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How hard the worker tries, and for how long.
 *
 * <p>A record bound at startup rather than a scatter of {@code @Value} fields:
 * every knob that governs the retry policy is in one place, typed, with its
 * default written next to it, and a typo in a property name is a startup failure
 * instead of a silent zero.
 *
 * @param maxAttempts   how many settlement attempts a payout gets before it is
 *                      failed and dead lettered. Counted at claim time, so a
 *                      worker that dies mid-settlement consumes one; that is the
 *                      conservative direction for money, because the alternative
 *                      is retrying a payment that may already have gone through
 * @param staleLock     how long a {@code PROCESSING} row may hold its lock
 *                      before another worker may take it. This is the recovery
 *                      window for a worker that died holding a payout, so it has
 *                      to be comfortably longer than a settlement call takes:
 *                      too short and two workers settle the same payout
 * @param pollInterval  delay between claim scans of the table
 * @param pollEnabled   whether the scan runs at all. Off in tests that have no
 *                      database, and a way to run a worker as a pure Kafka
 *                      consumer if one instance should own the sweeping
 * @param pollBatchSize how many due rows one scan claims. Bounded so a backlog
 *                      is worked through in steady batches rather than in one
 *                      transaction holding thousands of locks
 */
@ConfigurationProperties("payout.worker")
public record WorkerProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5m") Duration staleLock,
        @DefaultValue("15s") Duration pollInterval,
        @DefaultValue("true") boolean pollEnabled,
        @DefaultValue("50") int pollBatchSize) {
}
