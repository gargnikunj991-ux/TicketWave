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
import com.ticketwave.infrastructure.metrics.TicketWaveMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class BookingCheckoutService {

    private final IdempotencyService idempotencyService;
    private final PaymentService paymentService;
    private final BookingPersistenceService bookingPersistenceService;
    private final BookingRepository bookingRepository;
    private final ShowSeatRepository showSeatRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final TicketWaveMetrics metrics;

    public BookingCheckoutService(
            IdempotencyService idempotencyService,
            PaymentService paymentService,
            BookingPersistenceService bookingPersistenceService,
            BookingRepository bookingRepository,
            ShowSeatRepository showSeatRepository,
            StringRedisTemplate stringRedisTemplate) {
        this(idempotencyService, paymentService, bookingPersistenceService, bookingRepository, showSeatRepository, stringRedisTemplate, null);
    }

    @Autowired
    public BookingCheckoutService(
            IdempotencyService idempotencyService,
            PaymentService paymentService,
            BookingPersistenceService bookingPersistenceService,
            BookingRepository bookingRepository,
            ShowSeatRepository showSeatRepository,
            StringRedisTemplate stringRedisTemplate,
            @Autowired(required = false) TicketWaveMetrics metrics) {
        this.idempotencyService = idempotencyService;
        this.paymentService = paymentService;
        this.bookingPersistenceService = bookingPersistenceService;
        this.bookingRepository = bookingRepository;
        this.showSeatRepository = showSeatRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.metrics = metrics != null ? metrics : TicketWaveMetrics.noop();
    }

    /**
     * Executes idempotent checkout:
     * 1. Checks Redis idempotency cache (replays completed response or throws 409 if in progress).
     * 2. Validates hold existence, non-expiration, and user ownership.
     * 3. Executes simulated payment outside DB transaction.
     * 4. Atomically persists booking and tickets in PostgreSQL via BookingPersistenceService.
     * 5. Evicts Redis hold keys and caches completed response for 24 hours.
     */
    public CheckoutResponse checkout(String idempotencyKey, CheckoutRequest request) {
        long startNanos = System.nanoTime();
        metrics.recordCheckoutAttempt();

        log.info("Initiating checkout for user [{}] with hold [{}] and idempotency key [{}]",
                request.getUserId(), request.getHoldReference(), idempotencyKey);

        // 1. Idempotency Gate (Redis SETNX)
        Optional<CheckoutResponse> cached = idempotencyService.acquireOrGetCached(idempotencyKey);
        if (cached.isPresent()) {
            metrics.recordIdempotentReplay();
            return cached.get();
        }

        // 2. Hold-Level Concurrency Gate (Redis SETNX on holdReference)
        // Prevents two concurrent requests (even with different idempotency keys) from charging payment simultaneously for the same hold.
        String holdCheckoutLockKey = "lock:checkout:hold:" + request.getHoldReference();
        Boolean holdLockAcquired = null;
        try {
            holdLockAcquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(holdCheckoutLockKey, idempotencyKey, Duration.ofSeconds(60));
        } catch (Exception ignored) {}

        if (Boolean.FALSE.equals(holdLockAcquired)) {
            log.warn("Concurrent checkout attempt detected for hold reference [{}]", request.getHoldReference());
            throw new com.ticketwave.infrastructure.exception.IdempotencyConflictException(
                    "A checkout transaction for hold '" + request.getHoldReference() + "' is already in progress."
            );
        }

        try {
            // 3. Validate Hold Lease in DB and Redis
            List<ShowSeat> showSeats = validateAndGetHeldSeats(request.getHoldReference(), request.getUserId());

            BigDecimal totalAmount = showSeats.stream()
                    .map(ShowSeat::getPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 4. Process Payment (Outside DB @Transactional boundary to prevent HikariCP starvation)
            PaymentResult paymentResult = paymentService.processPayment(
                    request.getUserId(), totalAmount, request.getPaymentMethod(), request.getPaymentToken()
            );

            // 5. Atomic PostgreSQL Commit
            CheckoutResponse response = bookingPersistenceService.commitBooking(
                    showSeats, request, totalAmount, paymentResult.getTransactionId(), idempotencyKey
            );

            // 6. Post-Commit Redis Hold Invalidation & Idempotency Cache Update
            Long showId = showSeats.get(0).getShow().getId();
            cleanupRedisHolds(showId, request.getUserId(), request.getHoldReference(), showSeats);
            stringRedisTemplate.delete(holdCheckoutLockKey);
            idempotencyService.markCompleted(idempotencyKey, response);

            metrics.recordCheckoutSuccess(Duration.ofNanos(System.nanoTime() - startNanos));
            return response;

        } catch (Exception ex) {
            metrics.recordCheckoutFailure();
            stringRedisTemplate.delete(holdCheckoutLockKey);
            // If payment or hold validation fails, release in-progress idempotency lock so user can retry.
            // Note: If the failure is IdempotencyConflictException, another thread holds the lock and we MUST NOT release it!
            if (!(ex instanceof com.ticketwave.infrastructure.exception.IdempotencyConflictException)) {
                log.warn("Checkout failed for idempotency key [{}]: {}. Releasing lock.", idempotencyKey, ex.getMessage());
                idempotencyService.releaseLock(idempotencyKey);
            }
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
