package com.ticketwave.seat.reclamation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledHoldSweeperTest {

    @Mock
    private SeatReclamationService seatReclamationService;

    @InjectMocks
    private ScheduledHoldSweeper scheduledHoldSweeper;

    @Test
    @DisplayName("Scheduled sweeper triggers batch recovery on SeatReclamationService")
    void testSweepOrphanedHolds_TriggersReclamation() {
        when(seatReclamationService.sweepOrphanedHolds()).thenReturn(3);

        scheduledHoldSweeper.sweepOrphanedHolds();

        verify(seatReclamationService).sweepOrphanedHolds();
    }
}
