package com.ticketwave.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TicketWaveMetricsTest {

    private MeterRegistry meterRegistry;
    private TicketWaveMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new TicketWaveMetrics(meterRegistry);
    }

    @Test
    @DisplayName("Verify seat hold metrics tracking (attempts, successes, conflicts, lock timeouts, duration)")
    void testHoldMetricsTracking() {
        metrics.recordHoldAttempt();
        metrics.recordHoldAttempt();
        metrics.recordHoldSuccess(Duration.ofMillis(25));
        metrics.recordHoldConflict();
        metrics.recordHoldLockTimeout();

        assertThat(metrics.getHoldAttemptsCount()).isEqualTo(2.0);
        assertThat(metrics.getHoldSuccessesCount()).isEqualTo(1.0);
        assertThat(metrics.getHoldConflictsCount()).isEqualTo(1.0);
        assertThat(metrics.getHoldLockTimeoutsCount()).isEqualTo(1.0);

        assertThat(meterRegistry.find("ticketwave.holds.attempted").counter()).isNotNull();
        assertThat(meterRegistry.find("ticketwave.holds.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Verify checkout metrics tracking (attempts, successes, failures, idempotent replays, duration)")
    void testCheckoutMetricsTracking() {
        metrics.recordCheckoutAttempt();
        metrics.recordCheckoutAttempt();
        metrics.recordCheckoutSuccess(Duration.ofMillis(50));
        metrics.recordCheckoutFailure();
        metrics.recordIdempotentReplay();

        assertThat(metrics.getCheckoutAttemptsCount()).isEqualTo(2.0);
        assertThat(metrics.getCheckoutSuccessesCount()).isEqualTo(1.0);
        assertThat(metrics.getCheckoutFailuresCount()).isEqualTo(1.0);
        assertThat(metrics.getCheckoutIdempotentReplaysCount()).isEqualTo(1.0);

        assertThat(meterRegistry.find("ticketwave.checkout.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Verify auto-expiration inventory reclamation metrics tracking")
    void testReclamationMetricsTracking() {
        metrics.recordReactiveSweep();
        metrics.recordReactiveSweep();
        metrics.recordScheduledSweep(5);

        assertThat(metrics.getReactiveSweepsCount()).isEqualTo(2.0);
        assertThat(metrics.getScheduledSweepsCount()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("Verify noop instance works safely without crashing")
    void testNoopInstance() {
        TicketWaveMetrics noop = TicketWaveMetrics.noop();
        noop.recordHoldAttempt();
        noop.recordHoldSuccess(Duration.ofMillis(10));
        noop.recordCheckoutAttempt();

        assertThat(noop.getHoldAttemptsCount()).isEqualTo(1.0);
    }
}
