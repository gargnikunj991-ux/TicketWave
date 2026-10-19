package com.ticketwave.seat;

import com.ticketwave.infrastructure.exception.LockAcquisitionException;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import com.ticketwave.infrastructure.exception.SeatHoldConflictException;
import com.ticketwave.seat.dto.*;
import com.ticketwave.show.Show;
import com.ticketwave.show.ShowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.ticketwave.infrastructure.metrics.TicketWaveMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SeatHoldService {

    public static final long HOLD_TTL_SECONDS = 600; // 10 minutes
    public static final long LOCK_WAIT_MS = 2000;    // 2 seconds max wait
    public static final long LOCK_LEASE_MS = 5000;   // 5 seconds max hold on critical lock

    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ShowSeatRepository showSeatRepository;
    private final ShowRepository showRepository;
    private final TicketWaveMetrics metrics;

    public SeatHoldService(
            RedissonClient redissonClient,
            StringRedisTemplate stringRedisTemplate,
            ShowSeatRepository showSeatRepository,
            ShowRepository showRepository) {
        this(redissonClient, stringRedisTemplate, showSeatRepository, showRepository, null);
    }

    @Autowired
    public SeatHoldService(
            RedissonClient redissonClient,
            StringRedisTemplate stringRedisTemplate,
            ShowSeatRepository showSeatRepository,
            ShowRepository showRepository,
            @Autowired(required = false) TicketWaveMetrics metrics) {
        this.redissonClient = redissonClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.showSeatRepository = showSeatRepository;
        this.showRepository = showRepository;
        this.metrics = metrics != null ? metrics : TicketWaveMetrics.noop();
    }

    /**
     * 🎫 Retrieves real-time seat matrix for a given show.
     */
    @Transactional(readOnly = true)
    public SeatMatrixResponse getSeatMatrix(Long showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        List<ShowSeat> showSeats = showSeatRepository.findByShowIdWithSeatDetails(showId);

        List<SeatMatrixItemDto> seatDtos = showSeats.stream()
                .map(ss -> SeatMatrixItemDto.builder()
                        .seatId(ss.getSeat().getId())
                        .row(ss.getSeat().getRowLabel())
                        .number(ss.getSeat().getSeatNumber())
                        .tier(ss.getSeat().getTier())
                        .price(ss.getPrice())
                        .status(ss.getStatus())
                        .build())
                .toList();

        return SeatMatrixResponse.builder()
                .showId(show.getId())
                .eventName(show.getEvent().getTitle())
                .venueName(show.getScreen().getVenue().getName())
                .screenName(show.getScreen().getName())
                .startTime(show.getStartTime())
                .seats(seatDtos)
                .build();
    }

    /**
     * ⚡ Acquires a 10-minute temporary seat hold under high concurrency.
     *
     * 1. Sorts seat IDs in ascending order (deadlock-free natural ordering).
     * 2. Acquires Redisson MultiLock in Redis memory (<1ms).
     * 3. Verifies zero in-memory Redis holds and PostgreSQL AVAILABLE status.
     * 4. Sets Redis hold keys with 600s TTL.
     * 5. Persists HELD state and holdReference to PostgreSQL.
     */
    @Transactional
    public SeatHoldResponse acquireSeatHold(Long showId, SeatHoldRequest request) {
        long startNanos = System.nanoTime();
        metrics.recordHoldAttempt();

        // Validate Show
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ResourceNotFoundException("Show not found with id: " + showId));

        // 1. Deadlock Prevention: Deterministic natural ordering of seat IDs
        List<Long> sortedSeatIds = request.getSeatIds().stream()
                .distinct()
                .sorted()
                .toList();

        // Prepare distributed locks
        List<RLock> locks = sortedSeatIds.stream()
                .map(seatId -> redissonClient.getLock("lock:show:" + showId + ":seat:" + seatId))
                .toList();

        RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));

        boolean acquired;
        try {
            acquired = multiLock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.recordHoldLockTimeout();
            throw new LockAcquisitionException("Interrupted while waiting for seat locks for show: " + showId);
        }

        if (!acquired) {
            log.warn("Lock contention timeout on show [{}] for seats {}", showId, sortedSeatIds);
            metrics.recordHoldLockTimeout();
            throw new LockAcquisitionException("Unable to acquire lock on requested seats. High contention. Please try again.");
        }

        try {
            // 2. Fast Redis Check: Are any seats actively held?
            for (Long seatId : sortedSeatIds) {
                String holdKey = "hold:show:" + showId + ":seat:" + seatId;
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(holdKey))) {
                    metrics.recordHoldConflict();
                    throw new SeatHoldConflictException("Seat " + seatId + " is currently held by another user");
                }
            }

            // 3. PostgreSQL Consistency Check: Are seats available in the database?
            List<ShowSeat> showSeats = showSeatRepository.findByShowIdAndSeatIdIn(showId, sortedSeatIds);
            if (showSeats.size() != sortedSeatIds.size()) {
                throw new ResourceNotFoundException("One or more requested seats do not exist for this show");
            }

            Instant now = Instant.now();
            for (ShowSeat ss : showSeats) {
                boolean isExpiredZombieHold = ss.getStatus() == SeatStatus.HELD
                        && ss.getHoldExpiresAt() != null
                        && ss.getHoldExpiresAt().isBefore(now)
                        && !Boolean.TRUE.equals(stringRedisTemplate.hasKey("hold:show:" + showId + ":seat:" + ss.getSeat().getId()));

                if (ss.getStatus() != SeatStatus.AVAILABLE && !isExpiredZombieHold) {
                    metrics.recordHoldConflict();
                    throw new SeatHoldConflictException(
                            "Seat " + ss.getSeat().getRowLabel() + ss.getSeat().getSeatNumber() +
                            " is currently " + ss.getStatus()
                    );
                }
            }

            // 4. Generate Hold Reference & Expiry
            UUID holdReference = UUID.randomUUID();
            Instant expiresAt = Instant.now().plusSeconds(HOLD_TTL_SECONDS);

            BigDecimal totalAmount = showSeats.stream()
                    .map(ShowSeat::getPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 5. Set In-Memory Redis Holds with 10-Minute TTL
            for (Long seatId : sortedSeatIds) {
                String holdKey = "hold:show:" + showId + ":seat:" + seatId;
                String holdValue = holdReference + ":" + request.getUserId();
                stringRedisTemplate.opsForValue().set(holdKey, holdValue, Duration.ofSeconds(HOLD_TTL_SECONDS));
            }

            String userHoldKey = "user_hold:" + request.getUserId() + ":" + holdReference;
            String[] seatIdStrings = sortedSeatIds.stream().map(String::valueOf).toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(userHoldKey, seatIdStrings);
            stringRedisTemplate.expire(userHoldKey, Duration.ofSeconds(HOLD_TTL_SECONDS));

            // 6. Update PostgreSQL ShowSeats to HELD
            for (ShowSeat ss : showSeats) {
                ss.setStatus(SeatStatus.HELD);
                ss.setHoldReference(holdReference);
                ss.setHoldExpiresAt(expiresAt);
            }
            showSeatRepository.saveAll(showSeats);

            metrics.recordHoldSuccess(Duration.ofNanos(System.nanoTime() - startNanos));

            log.info("Successfully acquired hold [{}] for user [{}] on show [{}] for seats {}",
                    holdReference, request.getUserId(), showId, sortedSeatIds);

            return SeatHoldResponse.builder()
                    .holdReference(holdReference)
                    .showId(showId)
                    .userId(request.getUserId())
                    .seatIds(sortedSeatIds)
                    .totalHoldAmount(totalAmount)
                    .expiresAt(expiresAt)
                    .ttlSeconds(HOLD_TTL_SECONDS)
                    .build();

        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    /**
     * 🔓 Voluntary release of temporary seat holds.
     */
    @Transactional
    public HoldReleaseResponse releaseSeatHold(Long showId, HoldReleaseRequest request) {
        List<ShowSeat> heldSeats = showSeatRepository.findByHoldReference(request.getHoldReference());

        if (heldSeats.isEmpty()) {
            throw new ResourceNotFoundException("No active hold found for reference: " + request.getHoldReference());
        }

        // Cross-Show Validation
        if (!heldSeats.get(0).getShow().getId().equals(showId)) {
            throw new ResourceNotFoundException("Hold " + request.getHoldReference() + " does not belong to show: " + showId);
        }

        // Ownership / IDOR Validation
        String userHoldKey = "user_hold:" + request.getUserId() + ":" + request.getHoldReference();
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(userHoldKey))) {
            throw new SecurityException("User [" + request.getUserId() + "] is not authorized to release hold [" + request.getHoldReference() + "]");
        }

        // 1. Delete Redis Hold Keys
        for (ShowSeat ss : heldSeats) {
            String holdKey = "hold:show:" + showId + ":seat:" + ss.getSeat().getId();
            stringRedisTemplate.delete(holdKey);
        }

        stringRedisTemplate.delete(userHoldKey);

        // 2. Revert PostgreSQL ShowSeats to AVAILABLE
        for (ShowSeat ss : heldSeats) {
            ss.setStatus(SeatStatus.AVAILABLE);
            ss.setHoldReference(null);
            ss.setHoldExpiresAt(null);
        }
        showSeatRepository.saveAll(heldSeats);

        log.info("Voluntarily released hold [{}] on show [{}] for user [{}]",
                request.getHoldReference(), showId, request.getUserId());

        return HoldReleaseResponse.builder()
                .status("SUCCESS")
                .message("Hold released successfully. Seats are now available.")
                .build();
    }
}
