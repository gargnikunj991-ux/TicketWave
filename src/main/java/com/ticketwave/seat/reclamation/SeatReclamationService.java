package com.ticketwave.seat.reclamation;

import com.ticketwave.seat.SeatStatus;
import com.ticketwave.seat.ShowSeat;
import com.ticketwave.seat.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatReclamationService {

    private final ShowSeatRepository showSeatRepository;

    /**
     * Tier 1 (Reactive): Reclaims a single seat when its Redis hold key expires.
     * Guaranteed to be idempotent and safe:
     * - If the seat was already BOOKED (confirmed checkout), it is untouched!
     * - If the seat is already AVAILABLE, it is untouched.
     * - If the seat is HELD, it resets to AVAILABLE and clears hold metadata.
     */
    @Transactional
    public boolean reclaimSeatIfHeld(Long showId, Long seatId) {
        Optional<ShowSeat> optSeat = showSeatRepository.findByShowIdAndSeatId(showId, seatId);
        if (optSeat.isEmpty()) {
            log.debug("No ShowSeat found for showId [{}] and seatId [{}] during expiration reclamation", showId, seatId);
            return false;
        }

        ShowSeat ss = optSeat.get();
        if (ss.getStatus() == SeatStatus.BOOKED) {
            log.info("Seat [{}] for show [{}] is confirmed BOOKED. Skipping expiration reclamation.", seatId, showId);
            return false;
        }

        if (ss.getStatus() == SeatStatus.HELD) {
            ss.setStatus(SeatStatus.AVAILABLE);
            ss.setHoldReference(null);
            ss.setHoldExpiresAt(null);
            showSeatRepository.save(ss);
            log.info("Successfully reclaimed expired hold on show [{}] seat [{}] -> reset to AVAILABLE pool", showId, seatId);
            return true;
        }

        return false;
    }

    /**
     * Tier 2 (Proactive Sweeper): Scans PostgreSQL for orphaned holds whose hold_expires_at is in the past.
     * Ensures eventual consistency if Redis pub/sub messages were missed during network partitions or server restarts.
     */
    @Transactional
    public int sweepOrphanedHolds() {
        Instant now = Instant.now();
        List<ShowSeat> expiredHolds = showSeatRepository.findByStatusAndHoldExpiresAtBefore(SeatStatus.HELD, now);

        if (expiredHolds.isEmpty()) {
            return 0;
        }

        for (ShowSeat ss : expiredHolds) {
            ss.setStatus(SeatStatus.AVAILABLE);
            ss.setHoldReference(null);
            ss.setHoldExpiresAt(null);
        }

        showSeatRepository.saveAll(expiredHolds);
        log.info("Sweeper reclaimed [{}] orphaned seat holds whose TTL expired in PostgreSQL", expiredHolds.size());
        return expiredHolds.size();
    }
}
