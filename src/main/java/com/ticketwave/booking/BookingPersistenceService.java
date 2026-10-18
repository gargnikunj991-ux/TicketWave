package com.ticketwave.booking;

import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.booking.dto.TicketDto;
import com.ticketwave.seat.Seat;
import com.ticketwave.seat.SeatStatus;
import com.ticketwave.seat.ShowSeat;
import com.ticketwave.seat.ShowSeatRepository;
import com.ticketwave.show.Show;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingPersistenceService {

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final ShowSeatRepository showSeatRepository;

    /**
     * Atomically commits booking, tickets, and seat status transitions inside PostgreSQL.
     * Isolated at READ_COMMITTED level and runs in <10ms without any external network I/O.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CheckoutResponse commitBooking(
            List<ShowSeat> showSeats,
            CheckoutRequest request,
            BigDecimal totalAmount,
            String paymentTxnId,
            String idempotencyKey) {

        Show show = showSeats.get(0).getShow();

        // 1. Generate unique booking reference (e.g. TW-BK-A1B2C3D4)
        String bookingReference = "TW-BK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        // 2. Build and save Booking entity
        Booking booking = Booking.builder()
                .bookingReference(bookingReference)
                .show(show)
                .userId(request.getUserId())
                .totalAmount(totalAmount)
                .status(BookingStatus.CONFIRMED)
                .paymentTxnId(paymentTxnId)
                .idempotencyKey(idempotencyKey)
                .tickets(new ArrayList<>())
                .build();

        Booking savedBooking = bookingRepository.save(booking);

        // 3. Generate Tickets and transition ShowSeats to BOOKED
        List<TicketDto> ticketDtos = new ArrayList<>();
        int currentYear = Year.now().getValue();

        for (ShowSeat ss : showSeats) {
            Seat seat = ss.getSeat();

            // e.g. TKT-2026-001A01
            String ticketSerial = String.format("TKT-%d-%03d%s%02d",
                    currentYear, show.getId(), seat.getRowLabel(), seat.getSeatNumber());

            Ticket ticket = Ticket.builder()
                    .booking(savedBooking)
                    .seat(seat)
                    .ticketSerial(ticketSerial)
                    .price(ss.getPrice())
                    .build();

            Ticket savedTicket = ticketRepository.save(ticket);
            savedBooking.getTickets().add(savedTicket);

            // Update ShowSeat state
            ss.setStatus(SeatStatus.BOOKED);
            ss.setHoldReference(null);
            ss.setHoldExpiresAt(null);

            ticketDtos.add(TicketDto.builder()
                    .ticketSerial(savedTicket.getTicketSerial())
                    .seatId(seat.getId())
                    .row(seat.getRowLabel())
                    .number(seat.getSeatNumber())
                    .tier(seat.getTier())
                    .price(savedTicket.getPrice())
                    .build());
        }

        showSeatRepository.saveAll(showSeats);

        log.info("Successfully committed booking [{}] with [{}] tickets for user [{}]",
                bookingReference, ticketDtos.size(), request.getUserId());

        return CheckoutResponse.builder()
                .bookingReference(savedBooking.getBookingReference())
                .status(savedBooking.getStatus())
                .showId(show.getId())
                .userId(savedBooking.getUserId())
                .paymentTxnId(savedBooking.getPaymentTxnId())
                .totalAmount(savedBooking.getTotalAmount())
                .tickets(ticketDtos)
                .bookedAt(savedBooking.getCreatedAt() != null ? savedBooking.getCreatedAt() : Instant.now())
                .build();
    }
}
