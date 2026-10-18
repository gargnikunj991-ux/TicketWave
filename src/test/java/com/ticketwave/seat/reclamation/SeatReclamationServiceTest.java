package com.ticketwave.seat.reclamation;

import com.ticketwave.seat.*;
import com.ticketwave.show.Show;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatReclamationServiceTest {

    @Mock
    private ShowSeatRepository showSeatRepository;

    @InjectMocks
    private SeatReclamationService reclamationService;

    private ShowSeat heldSeat;
    private ShowSeat bookedSeat;
    private ShowSeat availableSeat;

    @BeforeEach
    void setUp() {
        Show show = Show.builder().id(1L).build();
        Seat seat = Seat.builder().id(101L).rowLabel("A").seatNumber(1).tier(SeatTier.VIP).build();

        heldSeat = ShowSeat.builder()
                .id(1L)
                .show(show)
                .seat(seat)
                .status(SeatStatus.HELD)
                .holdReference(UUID.randomUUID())
                .holdExpiresAt(Instant.now().minusSeconds(10))
                .price(BigDecimal.valueOf(250.00))
                .build();

        bookedSeat = ShowSeat.builder()
                .id(2L)
                .show(show)
                .seat(seat)
                .status(SeatStatus.BOOKED)
                .price(BigDecimal.valueOf(250.00))
                .build();

        availableSeat = ShowSeat.builder()
                .id(3L)
                .show(show)
                .seat(seat)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(250.00))
                .build();
    }

    @Test
    @DisplayName("Tier 1: Reclaims HELD seat to AVAILABLE and clears hold metadata")
    void testReclaimSeatIfHeld_HeldSeat_ResetToAvailable() {
        when(showSeatRepository.findByShowIdAndSeatId(1L, 101L)).thenReturn(Optional.of(heldSeat));

        boolean reclaimed = reclamationService.reclaimSeatIfHeld(1L, 101L);

        assertThat(reclaimed).isTrue();
        assertThat(heldSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(heldSeat.getHoldReference()).isNull();
        assertThat(heldSeat.getHoldExpiresAt()).isNull();
        verify(showSeatRepository).save(heldSeat);
    }

    @Test
    @DisplayName("Tier 1 Safety Guard: Never modifies confirmed BOOKED seats")
    void testReclaimSeatIfHeld_BookedSeat_Untouched() {
        when(showSeatRepository.findByShowIdAndSeatId(1L, 101L)).thenReturn(Optional.of(bookedSeat));

        boolean reclaimed = reclamationService.reclaimSeatIfHeld(1L, 101L);

        assertThat(reclaimed).isFalse();
        assertThat(bookedSeat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        verify(showSeatRepository, never()).save(any(ShowSeat.class));
    }

    @Test
    @DisplayName("Tier 1: Returns false if seat is already AVAILABLE")
    void testReclaimSeatIfHeld_AlreadyAvailable_Untouched() {
        when(showSeatRepository.findByShowIdAndSeatId(1L, 101L)).thenReturn(Optional.of(availableSeat));

        boolean reclaimed = reclamationService.reclaimSeatIfHeld(1L, 101L);

        assertThat(reclaimed).isFalse();
        verify(showSeatRepository, never()).save(any(ShowSeat.class));
    }

    @Test
    @DisplayName("Tier 1: Returns false when seat is not found")
    void testReclaimSeatIfHeld_NotFound() {
        when(showSeatRepository.findByShowIdAndSeatId(1L, 999L)).thenReturn(Optional.empty());

        boolean reclaimed = reclamationService.reclaimSeatIfHeld(1L, 999L);

        assertThat(reclaimed).isFalse();
        verify(showSeatRepository, never()).save(any(ShowSeat.class));
    }

    @Test
    @DisplayName("Tier 2 Sweeper: Bulk reclaims multiple expired holds in PostgreSQL")
    void testSweepOrphanedHolds_ReclaimsExpiredSeatsInBatch() {
        ShowSeat expiredSeat2 = ShowSeat.builder()
                .id(4L)
                .status(SeatStatus.HELD)
                .holdReference(UUID.randomUUID())
                .holdExpiresAt(Instant.now().minusSeconds(60))
                .build();

        when(showSeatRepository.findByStatusAndHoldExpiresAtBefore(eq(SeatStatus.HELD), any(Instant.class)))
                .thenReturn(List.of(heldSeat, expiredSeat2));

        int sweptCount = reclamationService.sweepOrphanedHolds();

        assertThat(sweptCount).isEqualTo(2);
        assertThat(heldSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(heldSeat.getHoldReference()).isNull();
        assertThat(expiredSeat2.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(expiredSeat2.getHoldReference()).isNull();
        verify(showSeatRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("Tier 2 Sweeper: Returns 0 when no orphaned holds exist")
    void testSweepOrphanedHolds_NoExpiredHolds() {
        when(showSeatRepository.findByStatusAndHoldExpiresAtBefore(eq(SeatStatus.HELD), any(Instant.class)))
                .thenReturn(List.of());

        int sweptCount = reclamationService.sweepOrphanedHolds();

        assertThat(sweptCount).isEqualTo(0);
        verify(showSeatRepository, never()).saveAll(anyList());
    }
}
