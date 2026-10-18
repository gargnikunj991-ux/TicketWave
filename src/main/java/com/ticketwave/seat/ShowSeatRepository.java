package com.ticketwave.seat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    @Query("SELECT ss FROM ShowSeat ss JOIN FETCH ss.seat WHERE ss.show.id = :showId ORDER BY ss.seat.rowLabel ASC, ss.seat.seatNumber ASC")
    List<ShowSeat> findByShowIdWithSeatDetails(@Param("showId") Long showId);

    List<ShowSeat> findByShowIdAndStatus(Long showId, SeatStatus status);

    @Query("SELECT ss FROM ShowSeat ss JOIN FETCH ss.seat WHERE ss.show.id = :showId AND ss.seat.id IN :seatIds")
    List<ShowSeat> findByShowIdAndSeatIdIn(@Param("showId") Long showId, @Param("seatIds") Collection<Long> seatIds);

    List<ShowSeat> findByHoldReference(UUID holdReference);

    @Query("SELECT ss FROM ShowSeat ss JOIN FETCH ss.seat JOIN FETCH ss.show WHERE ss.holdReference = :holdReference")
    List<ShowSeat> findByHoldReferenceWithDetails(@Param("holdReference") UUID holdReference);

    List<ShowSeat> findByStatusAndHoldExpiresAtBefore(SeatStatus status, Instant expirationThreshold);
}
