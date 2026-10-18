package com.ticketwave.booking;

import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.booking.dto.TicketDto;
import com.ticketwave.booking.idempotency.IdempotencyService;
import com.ticketwave.booking.payment.PaymentResult;
import com.ticketwave.booking.payment.PaymentService;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import com.ticketwave.infrastructure.exception.SeatHoldExpiredException;
import com.ticketwave.seat.SeatStatus;
import com.ticketwave.seat.ShowSeat;
import com.ticketwave.seat.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingCheckoutService {

    private final IdempotencyService idempotencyService;
    private final PaymentService paymentService;
    private final BookingPersistenceService bookingPersistenceService;
    private final BookingRepository bookingRepository;
    private final ShowSeatRepository showSeatRepository;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Executes idempotent checkout:
     * 1. Checks Redis idempotency cache (replays completed response or throws 409 if in progress).
     * 2. Validates hold existence, non-expiration, and user ownership.
     * 3. Executes simulated payment outside DB transaction.
     * 4. Atomically persists booking and tickets in PostgreSQL via BookingPersistenceService.
     * 5. Evicts Redis hold keys and caches completed response for 24 hours.
     */
    public CheckoutResponse checkout(String idempotencyKey, CheckoutRequest request) {
        log.info("Initiating checkout for user [{}] with hold [{}] and idempotency key [{}]",
                request.getUserId(), request.getHoldReference(), idempotencyKey);

        // 1. Idempotency Gate (Redis SETNX)
        Optional<CheckoutResponse> cached = idempotencyService.acquireOrGetCached(idempotencyKey);
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            // 2. Validate Hold Lease in DB and Redis
            List<ShowSeat> showSeats = validateAndGetHeldSeats(request.getHoldReference(), request.getUserId());

            BigDecimal totalAmount = showSeats.stream()
                    .map(ShowSeat::getPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 3. Process Payment (Outside DB @Transactional boundary to prevent HikariCP starvation)
            PaymentResult paymentResult = paymentService.processPayment(
                    request.getUserId(), totalAmount, request.getPaymentMethod(), request.getPaymentToken()
            );

            // 4. Atomic PostgreSQL Commit
            CheckoutResponse response = bookingPersistenceService.commitBooking(
                    showSeats, request, totalAmount, paymentResult.getTransactionId(), idempotencyKey
            );

            // 5. Post-Commit Redis Hold Invalidation & Idempotency Cache Update
            Long showId = showSeats.get(0).getShow().getId();
            cleanupRedisHolds(showId, request.getUserId(), request.getHoldReference(), showSeats);
            idempotencyService.markCompleted(idempotencyKey, response);

            return response;

        } catch (Exception ex) {
            // If payment or hold validation fails, release in-progress idempotency lock so user can retry
            log.warn("Checkout failed for idempotency key [{}]: {}. Releasing lock.", idempotencyKey, ex.getMessage());
            idempotencyService.releaseLock(idempotencyKey);
            throw ex;
        }
    }

    /**
     * Retrieves confirmed booking details by booking reference.
     */
    @Transactional(readOnly = true)
    public CheckoutResponse getBookingByReference(String bookingReference) {
        Booking booking = bookingRepository.findByBookingReferenceWithTickets(bookingReference)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found with reference: " + bookingReference));

        List<TicketDto> ticketDtos = booking.getTickets().stream()
                .map(t -> TicketDto.builder()
                        .ticketSerial(t.getTicketSerial())
                        .seatId(t.getSeat().getId())
                        .row(t.getSeat().getRowLabel())
                        .number(t.getSeat().getSeatNumber())
                        .tier(t.getSeat().getTier())
                        .price(t.getPrice())
                        .build())
                .toList();

        return CheckoutResponse.builder()
                .bookingReference(booking.getBookingReference())
                .status(booking.getStatus())
                .showId(booking.getShow().getId())
                .userId(booking.getUserId())
                .paymentTxnId(booking.getPaymentTxnId())
                .totalAmount(booking.getTotalAmount())
                .tickets(ticketDtos)
                .bookedAt(booking.getCreatedAt())
                .build();
    }

    /**
     * Validates that the hold exists, belongs to the requesting user, and has not expired.
     */
    private List<ShowSeat> validateAndGetHeldSeats(UUID holdReference, String userId) {
        List<ShowSeat> showSeats = showSeatRepository.findByHoldReferenceWithDetails(holdReference);

        if (showSeats.isEmpty()) {
            throw new SeatHoldExpiredException("Hold reference not found or already expired: " + holdReference);
        }

        Instant now = Instant.now();
        Long showId = showSeats.get(0).getShow().getId();

        for (ShowSeat ss : showSeats) {
            if (ss.getStatus() != SeatStatus.HELD) {
                throw new SeatHoldExpiredException("Seat " + ss.getSeat().getId() + " is no longer HELD (current status: " + ss.getStatus() + ")");
            }

            if (ss.getHoldExpiresAt() == null || ss.getHoldExpiresAt().isBefore(now)) {
                throw new SeatHoldExpiredException("Seat hold has expired. Seats returned to inventory pool.");
            }

            // In-memory Redis lease verification
            String holdKey = "hold:show:" + showId + ":seat:" + ss.getSeat().getId();
            String holdVal = stringRedisTemplate.opsForValue().get(holdKey);
            if (holdVal == null) {
                throw new SeatHoldExpiredException("Redis lease expired for seat " + ss.getSeat().getId() + ". Hold is invalid.");
            }

            // IDOR / Ownership validation (stored as "{holdReference}:{userId}")
            String[] parts = holdVal.split(":");
            if (parts.length >= 2 && !userId.equals(parts[1])) {
                throw new IllegalArgumentException("User [" + userId + "] is not authorized to checkout hold [" + holdReference + "]");
            }
        }

        return showSeats;
    }

    /**
     * Removes Redis hold keys after successful booking.
     */
    private void cleanupRedisHolds(Long showId, String userId, UUID holdReference, List<ShowSeat> showSeats) {
        try {
            for (ShowSeat ss : showSeats) {
                String holdKey = "hold:show:" + showId + ":seat:" + ss.getSeat().getId();
                stringRedisTemplate.delete(holdKey);
            }
            String userHoldKey = "user_hold:" + userId + ":" + holdReference;
            stringRedisTemplate.delete(userHoldKey);
            log.info("Purged Redis hold keys for show [{}] and holdReference [{}]", showId, holdReference);
        } catch (Exception e) {
            log.error("Failed to clean up Redis hold keys for show [{}] and hold [{}]", showId, holdReference, e);
        }
    }
}
