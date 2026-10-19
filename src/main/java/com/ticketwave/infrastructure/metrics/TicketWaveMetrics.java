package com.ticketwave.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 📊 TicketWave Production Observability & Micrometer Metrics Engine
 *
 * Exposes real-time operational telemetry for Prometheus & Spring Actuator:
 * - Seat Hold metrics (attempted, succeeded, conflicts, lock timeouts, duration)
 * - Checkout metrics (attempted, succeeded, payment failures, idempotent replays, duration)
 * - Auto-expiration inventory reclamation metrics (reactive sweeps, scheduled batch sweeps)
 */
@Component
public class TicketWaveMetrics {

    private final MeterRegistry meterRegistry;

    // Hold Metrics
    private final Counter holdAttempts;
    private final Counter holdSuccesses;
    private final Counter holdConflicts;
    private final Counter holdLockTimeouts;
    private final Timer holdDuration;

    // Checkout Metrics
    private final Counter checkoutAttempts;
    private final Counter checkoutSuccesses;
    private final Counter checkoutFailures;
    private final Counter checkoutIdempotentReplays;
    private final Timer checkoutDuration;

    // Inventory Reclamation Metrics
    private final Counter reactiveSweeps;
    private final Counter scheduledSweeps;

    public TicketWaveMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();

        this.holdAttempts = Counter.builder("ticketwave.holds.attempted")
                .description("Total number of seat hold acquisition requests")
                .register(this.meterRegistry);

        this.holdSuccesses = Counter.builder("ticketwave.holds.success")
                .description("Number of successfully acquired seat holds")
                .register(this.meterRegistry);

        this.holdConflicts = Counter.builder("ticketwave.holds.conflict")
                .description("Number of seat hold attempts rejected due to 409 Conflict")
                .register(this.meterRegistry);

        this.holdLockTimeouts = Counter.builder("ticketwave.holds.lock_timeout")
                .description("Number of seat hold attempts failing due to distributed lock contention timeout")
                .register(this.meterRegistry);

        this.holdDuration = Timer.builder("ticketwave.holds.duration")
                .description("Latency distribution of seat hold acquisitions")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(this.meterRegistry);

        this.checkoutAttempts = Counter.builder("ticketwave.checkout.attempted")
                .description("Total checkout and payment attempts initiated")
                .register(this.meterRegistry);

        this.checkoutSuccesses = Counter.builder("ticketwave.checkout.success")
                .description("Number of successfully finalized bookings")
                .register(this.meterRegistry);

        this.checkoutFailures = Counter.builder("ticketwave.checkout.failed")
                .description("Number of failed checkouts due to payment decline or lease expiration")
                .register(this.meterRegistry);

        this.checkoutIdempotentReplays = Counter.builder("ticketwave.checkout.idempotent_replays")
                .description("Number of checkouts served from 24h idempotency cache replay")
                .register(this.meterRegistry);

        this.checkoutDuration = Timer.builder("ticketwave.checkout.duration")
                .description("Latency distribution of checkout execution")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(this.meterRegistry);

        this.reactiveSweeps = Counter.builder("ticketwave.reclamation.reactive_sweeps")
                .description("Number of seats recovered reactively via Redis keyspace expiration events")
                .register(this.meterRegistry);

        this.scheduledSweeps = Counter.builder("ticketwave.reclamation.scheduled_sweeps")
                .description("Number of orphaned seats recovered by proactive 60s background sweeper")
                .register(this.meterRegistry);
    }

    public static TicketWaveMetrics noop() {
        return new TicketWaveMetrics(new SimpleMeterRegistry());
    }

    // --- Hold Recording Methods ---
    public void recordHoldAttempt() {
        holdAttempts.increment();
    }

    public void recordHoldSuccess(Duration duration) {
        holdSuccesses.increment();
        if (duration != null) {
            holdDuration.record(duration);
        }
    }

    public void recordHoldConflict() {
        holdConflicts.increment();
    }

    public void recordHoldLockTimeout() {
        holdLockTimeouts.increment();
    }

    // --- Checkout Recording Methods ---
    public void recordCheckoutAttempt() {
        checkoutAttempts.increment();
    }

    public void recordCheckoutSuccess(Duration duration) {
        checkoutSuccesses.increment();
        if (duration != null) {
            checkoutDuration.record(duration);
        }
    }

    public void recordCheckoutFailure() {
        checkoutFailures.increment();
    }

    public void recordIdempotentReplay() {
        checkoutIdempotentReplays.increment();
    }

    // --- Reclamation Recording Methods ---
    public void recordReactiveSweep() {
        reactiveSweeps.increment();
    }

    public void recordScheduledSweep(int reclaimedCount) {
        scheduledSweeps.increment(reclaimedCount);
    }

    // --- Getters for Unit Testing ---
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    public double getHoldAttemptsCount() {
        return holdAttempts.count();
    }

    public double getHoldSuccessesCount() {
        return holdSuccesses.count();
    }

    public double getHoldConflictsCount() {
        return holdConflicts.count();
    }

    public double getHoldLockTimeoutsCount() {
        return holdLockTimeouts.count();
    }

    public double getCheckoutAttemptsCount() {
        return checkoutAttempts.count();
    }

    public double getCheckoutSuccessesCount() {
        return checkoutSuccesses.count();
    }

    public double getCheckoutFailuresCount() {
        return checkoutFailures.count();
    }

    public double getCheckoutIdempotentReplaysCount() {
        return checkoutIdempotentReplays.count();
    }

    public double getReactiveSweepsCount() {
        return reactiveSweeps.count();
    }

    public double getScheduledSweepsCount() {
        return scheduledSweeps.count();
    }
}
