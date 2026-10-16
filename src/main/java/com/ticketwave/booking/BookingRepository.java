package com.ticketwave.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT b FROM Booking b LEFT JOIN FETCH b.tickets WHERE b.bookingReference = :ref")
    Optional<Booking> findByBookingReferenceWithTickets(@Param("ref") String bookingReference);

    List<Booking> findByUserIdOrderByCreatedAtDesc(String userId);
}
