package com.ticketwave.seat.reclamation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledHoldSweeper {

    private final SeatReclamationService seatReclamationService;

    /**
     * Tier 2 (Proactive): Runs every 60 seconds as a safety reconciliation worker.
     * Reclaims any orphaned holds in PostgreSQL whose hold_expires_at is in the past,
     * protecting against missed Redis pub/sub events.
     */
    @Scheduled(fixedRate = 60000)
    public void sweepOrphanedHolds() {
        log.debug("Starting proactive scheduled sweep for orphaned seat holds...");
        int reclaimedCount = seatReclamationService.sweepOrphanedHolds();
        if (reclaimedCount > 0) {
            log.info("Proactive sweep finished: Reclaimed [{}] orphaned seat holds to AVAILABLE pool", reclaimedCount);
        }
    }
}
