package com.ticketwave.flaws;

import com.ticketwave.booking.BookingCheckoutService;
import com.ticketwave.booking.BookingPersistenceService;
import com.ticketwave.booking.BookingRepository;
import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.booking.idempotency.IdempotencyService;
import com.ticketwave.booking.payment.PaymentResult;
import com.ticketwave.booking.payment.PaymentService;
import com.ticketwave.booking.payment.PaymentStatus;
import com.ticketwave.event.Event;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import com.ticketwave.infrastructure.metrics.TicketWaveMetrics;
import com.ticketwave.seat.Seat;
import com.ticketwave.seat.SeatHoldService;
import com.ticketwave.seat.SeatStatus;
import com.ticketwave.seat.SeatTier;
import com.ticketwave.seat.ShowSeat;
import com.ticketwave.seat.ShowSeatRepository;
import com.ticketwave.seat.dto.HoldReleaseRequest;
import com.ticketwave.seat.dto.SeatHoldRequest;
import com.ticketwave.seat.dto.SeatHoldResponse;
import com.ticketwave.seat.dto.SeatMatrixResponse;
import com.ticketwave.seat.reclamation.RedisKeyExpirationListener;
import com.ticketwave.seat.reclamation.ScheduledHoldSweeper;
import com.ticketwave.seat.reclamation.SeatReclamationService;
import com.ticketwave.show.Show;
import com.ticketwave.show.ShowRepository;
import com.ticketwave.show.ShowStatus;
import com.ticketwave.venue.Screen;
import com.ticketwave.venue.ScreenType;
import com.ticketwave.venue.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 🚨 SYSTEM ARCHITECTURAL FLAWS & EDGE CASES TEST SUITE
 *
 * This test suite exposes the deep architectural vulnerabilities and failure modes
 * present in the TicketWave distributed reservation and checkout pipeline:
 *
 * 1. FLAW 1: Dual-Write Financial Inconsistency & Idempotency Key Eviction on DB Crash
 *    - Funds debited by payment gateway, DB persistence fails, NO compensating refund exists.
 *    - In-progress idempotency key is released, permitting duplicate debit on client retry.
 *
 * 2. FLAW 2: Redis Ghost Holds on Database Persistence Failure
 *    - Redis holds written with 10m TTL before DB commit. When DB save fails, Redisson
 *      locks are released, but Redis hold keys are orphaned without rollback.
 *    - The hold cannot even be voluntarily cancelled because DB lacks hold record (404).
 *
 * 3. FLAW 3: Delayed Redis Expiration Pub/Sub Wipes Active Re-Hold
 *    - SeatReclamationService.reclaimSeatIfHeld resets ANY 'HELD' seat to 'AVAILABLE'.
 *    - It does not check if hold_expires_at is in the future or verify holdReference.
 *    - Delayed Redis keyevent wipes out a fresh buyer's hold mid-checkout.
 *
 * 4. FLAW 4: Hold Expiration Race During In-Flight Payment Gateway Call
 *    - Checkout validates hold before calling external payment gateway.
 *    - If payment takes 2s and hold expires mid-flight, another user can claim the seat.
 *    - Subsequent DB commit fails with OptimisticLockException AFTER payment succeeded!
 *
 * 5. FLAW 5: Uncoordinated Cluster Sweeper Contention
 *    - ScheduledHoldSweeper runs on every cluster node without distributed lock (ShedLock).
 *    - Multiple instances concurrently sweep identical rows, causing DB lock contention.
 *
 * 6. FLAW 6: Read-Side Cache Incoherence in Seat Matrix
 *    - getSeatMatrix reads strictly from PostgreSQL, returning 'HELD' for expired seats
 *      that have not yet been swept, deceiving users into thinking seats are taken.
 */
@ExtendWith(MockitoExtension.class)
class TicketWaveArchitecturalFlawsTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private PaymentService paymentService;

    @Mock
    private BookingPersistenceService bookingPersistenceService;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private ShowSeatRepository showSeatRepository;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock mockLock;

    @Mock
    private RLock mockMultiLock;

    private Show testShow;
    private Seat testSeat;
    private ShowSeat testShowSeat;
    private UUID testHoldReference;

    @BeforeEach
    void setUp() {
        Venue venue = Venue.builder().id(1L).name("Madison Square Garden").build();
        Screen screen = Screen.builder().id(1L).venue(venue).name("Main Arena").screenType(ScreenType.IMAX).build();
        Event event = Event.builder().id(1L).title("Coldplay Music of the Spheres").build();

        testShow = Show.builder()
                .id(1L)
                .event(event)
                .screen(screen)
                .startTime(Instant.now().plusSeconds(3600))
                .endTime(Instant.now().plusSeconds(7200))
                .status(ShowStatus.ACTIVE)
                .build();

        testSeat = Seat.builder()
                .id(101L)
                .screen(screen)
                .rowLabel("A")
                .seatNumber(1)
                .tier(SeatTier.VIP)
                .build();

        testHoldReference = UUID.randomUUID();

        testShowSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(testSeat)
                .status(SeatStatus.HELD)
                .holdReference(testHoldReference)
                .holdExpiresAt(Instant.now().plusSeconds(600))
                .price(BigDecimal.valueOf(250.00))
                .version(1L)
                .build();
    }

    // =========================================================================
    // FLAW 1: DUAL-WRITE FINANCIAL INCONSISTENCY (MONEY DEBITED, NO BOOKING, NO REFUND)
    // =========================================================================

    @Test
    @DisplayName("FLAW 1: DB persistence failure after successful payment debits customer without booking or refund, and allows duplicate charge on retry")
    void testFlaw1_UncompensatedDebitAndIdempotencyLockLeakOnDatabaseFailure() {
        // Arrange
        BookingCheckoutService checkoutService = new BookingCheckoutService(
                idempotencyService, paymentService, bookingPersistenceService,
                bookingRepository, showSeatRepository, stringRedisTemplate, TicketWaveMetrics.noop()
        );

        String idempotencyKey = "idemp-crash-test-001";
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(testHoldReference)
                .paymentMethod("STRIPE")
                .paymentToken("tok_mock_success_alice")
                .build();

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.empty());
        when(valueOperations.setIfAbsent(eq("lock:checkout:hold:" + testHoldReference), eq(idempotencyKey), any(Duration.class)))
                .thenReturn(true);

        when(showSeatRepository.findByHoldReferenceWithDetails(testHoldReference))
                .thenReturn(List.of(testShowSeat));
        when(valueOperations.get("hold:show:1:seat:101"))
                .thenReturn(testHoldReference + ":usr_alice");

        // Payment gateway charges Alice's card successfully ($250.00 debited)
        PaymentResult paymentSuccess = PaymentResult.builder()
                .status(PaymentStatus.SUCCESS)
                .transactionId("TXN_STRIPE_CAPTURE_99999")
                .message("Captured")
                .build();
        when(paymentService.processPayment(eq("usr_alice"), eq(BigDecimal.valueOf(250.00)), eq("STRIPE"), eq("tok_mock_success_alice")))
                .thenReturn(paymentSuccess);

        // Database connection pool dies or PostgreSQL disk fills up during commitBooking!
        when(bookingPersistenceService.commitBooking(anyList(), eq(request), any(BigDecimal.class), eq("TXN_STRIPE_CAPTURE_99999"), eq(idempotencyKey)))
                .thenThrow(new CannotAcquireLockException("HikariPool-1 - Connection is not available, request timed out after 30000ms."));

        // Act & Assert 1: The checkout fails with DB exception
        assertThatThrownBy(() -> checkoutService.checkout(idempotencyKey, request))
                .isInstanceOf(CannotAcquireLockException.class);

        // VULNERABILITY EXPOSURE:
        // 1. Payment gateway was called and captured Alice's funds ($250.00).
        verify(paymentService, times(1)).processPayment(anyString(), any(BigDecimal.class), anyString(), anyString());

        // 2. NO refund was ever issued! (Notice PaymentService doesn't even have a refund method,
        //    nor was any compensating transaction scheduled). Alice has lost $250.00 with NO tickets.
        verify(bookingPersistenceService, times(1)).commitBooking(anyList(), any(), any(), any(), any());

        // 3. AGGRAVATING FLAW: The catch block calls idempotencyService.releaseLock(idempotencyKey)!
        // Because of this, the in-progress lock was deleted.
        verify(idempotencyService, times(1)).releaseLock(idempotencyKey);

        // 4. If Alice's browser or mobile app retries the identical request with the same idempotency key:
        // Because the lock was released, acquireOrGetCached will NOT replay a cached response,
        // and paymentService.processPayment will be called AGAIN, charging Alice a SECOND time!
    }

    // =========================================================================
    // FLAW 2: REDIS GHOST HOLDS ORPHANED ON DB PERSISTENCE CRASH
    // =========================================================================

    @Test
    @DisplayName("FLAW 2: Database failure during acquireSeatHold leaves ghost holds in Redis, blocking seats for 10 minutes with no DB record")
    void testFlaw2_GhostRedisHoldsOrphanedOnDatabasePersistenceFailure() throws Exception {
        // Arrange
        SeatHoldService seatHoldService = new SeatHoldService(
                redissonClient, stringRedisTemplate, showSeatRepository, showRepository, TicketWaveMetrics.noop()
        );

        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));
        when(redissonClient.getLock(anyString())).thenReturn(mockLock);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(mockMultiLock);
        when(mockMultiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(mockMultiLock.isHeldByCurrentThread()).thenReturn(true);

        ShowSeat availableSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(testSeat)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(250.00))
                .build();

        when(showSeatRepository.findByShowIdAndSeatIdIn(eq(1L), anyList()))
                .thenReturn(List.of(availableSeat));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        // Step 6: PostgreSQL saveAll throws unexpected database exception!
        when(showSeatRepository.saveAll(anyList()))
                .thenThrow(new RuntimeException("PostgreSQL disk write error / Connection aborted"));

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_alice")
                .seatIds(List.of(101L))
                .build();

        // Act: acquireSeatHold throws exception
        assertThatThrownBy(() -> seatHoldService.acquireSeatHold(1L, request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("PostgreSQL disk write error");

        // VULNERABILITY EXPOSURE:
        // 1. Redisson lock was unlocked in finally block
        verify(mockMultiLock, times(1)).unlock();

        // 2. Redis hold key WAS set with 600s TTL in Step 5 before the DB write failed:
        verify(valueOperations, times(1)).set(eq("hold:show:1:seat:101"), anyString(), eq(Duration.ofSeconds(600)));
        verify(setOperations, times(1)).add(startsWith("user_hold:usr_alice:"), any(String[].class));

        // 3. BUT Redis keys were NEVER cleaned up or rolled back! (delete was never called for hold:show:1:seat:101)
        verify(stringRedisTemplate, never()).delete("hold:show:1:seat:101");
        verify(stringRedisTemplate, never()).delete(startsWith("user_hold:usr_alice:"));

        // 4. CONSEQUENCE: The seat is now a "Ghost Hold" in Redis for 10 minutes.
        // In PostgreSQL, availableSeat is STILL 'AVAILABLE'.
        // If Alice tries to call releaseSeatHold to undo it:
        // releaseSeatHold queries showSeatRepository.findByHoldReference(...), which returns EMPTY because DB save failed!
        // Alice receives 404 ResourceNotFoundException and CANNOT even voluntarily release the ghost hold!
    }

    // =========================================================================
    // FLAW 3: DELAYED REDIS EXPIRATION PUB/SUB WIPES FRESH ACTIVE RE-HOLD
    // =========================================================================

    @Test
    @DisplayName("FLAW 3: Delayed Redis keyevent expiration wipes out a brand-new, active hold because reclaimSeatIfHeld lacks timestamp validation")
    void testFlaw3_PrematureEvictionOfActiveHoldByDelayedRedisExpirationPubSub() {
        // Arrange
        SeatReclamationService reclamationService = new SeatReclamationService(
                showSeatRepository, TicketWaveMetrics.noop()
        );
        RedisKeyExpirationListener listener = new RedisKeyExpirationListener(reclamationService);

        // Suppose User Bob acquired Seat 101 literally 10 seconds ago.
        // His hold expires 590 seconds in the future!
        UUID bobHoldReference = UUID.randomUUID();
        Instant futureExpiry = Instant.now().plusSeconds(590);

        ShowSeat bobsActiveSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(testSeat)
                .status(SeatStatus.HELD)
                .holdReference(bobHoldReference)
                .holdExpiresAt(futureExpiry)
                .price(BigDecimal.valueOf(250.00))
                .build();

        when(showSeatRepository.findByShowIdAndSeatId(1L, 101L))
                .thenReturn(Optional.of(bobsActiveSeat));

        // Now, Redis pub/sub delivers a delayed keyevent for a PREVIOUS hold on "hold:show:1:seat:101"
        // that expired moments earlier.
        boolean reclaimed = listener.processExpiredKey("hold:show:1:seat:101");

        // VULNERABILITY EXPOSURE:
        // 1. reclaimSeatIfHeld returns true and wipes out the seat hold!
        assertThat(reclaimed).isTrue();

        // 2. Bob's active hold was reset to AVAILABLE and holdReference set to null,
        // even though Bob's hold had 9 minutes and 50 seconds remaining!
        assertThat(bobsActiveSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(bobsActiveSeat.getHoldReference()).isNull();
        assertThat(bobsActiveSeat.getHoldExpiresAt()).isNull();

        // 3. The seat was saved as AVAILABLE in PostgreSQL:
        verify(showSeatRepository, times(1)).save(bobsActiveSeat);

        // 4. Bob is now actively entering credit card info, but his seat was wiped out and
        // another buyer can snatch it!
    }

    // =========================================================================
    // FLAW 4: HOLD EXPIRED DURING IN-FLIGHT PAYMENT (OPTIMISTIC LOCK FAILURE & CHARGED WITHOUT SEAT)
    // =========================================================================

    @Test
    @DisplayName("FLAW 4: When hold expires mid-flight during payment gateway processing, customer is charged but booking fails with OptimisticLockException")
    void testFlaw4_HoldExpiresDuringInFlightPaymentCausesDebitWithoutBooking() {
        // Arrange
        BookingCheckoutService checkoutService = new BookingCheckoutService(
                idempotencyService, paymentService, bookingPersistenceService,
                bookingRepository, showSeatRepository, stringRedisTemplate, TicketWaveMetrics.noop()
        );

        String idempotencyKey = "idemp-midflight-race";
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(testHoldReference)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_alice")
                .build();

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.empty());
        when(valueOperations.setIfAbsent(eq("lock:checkout:hold:" + testHoldReference), eq(idempotencyKey), any(Duration.class)))
                .thenReturn(true);

        // Step 3 (Initial validation): Alice's hold is valid (1 second left before expiry)
        when(showSeatRepository.findByHoldReferenceWithDetails(testHoldReference))
                .thenReturn(List.of(testShowSeat));
        when(valueOperations.get("hold:show:1:seat:101"))
                .thenReturn(testHoldReference + ":usr_alice");

        // Step 4: External Payment gateway takes 2 seconds and succeeds ($250 charged)
        PaymentResult paymentResult = PaymentResult.builder()
                .status(PaymentStatus.SUCCESS)
                .transactionId("TXN_UPI_RACE_123")
                .message("Approved")
                .build();
        when(paymentService.processPayment(eq("usr_alice"), eq(BigDecimal.valueOf(250.00)), eq("UPI"), eq("tok_mock_success_alice")))
                .thenReturn(paymentResult);

        // Step 5: During the 2-second payment, hold expired and background sweeper reclaimed the seat,
        // or another customer Bob booked it. The DB row's @Version was updated from 1 to 2.
        // Alice's commitBooking receives ObjectOptimisticLockingFailureException!
        when(bookingPersistenceService.commitBooking(anyList(), eq(request), any(BigDecimal.class), eq("TXN_UPI_RACE_123"), eq(idempotencyKey)))
                .thenThrow(new ObjectOptimisticLockingFailureException(ShowSeat.class, 1L));

        // Act & Assert
        assertThatThrownBy(() -> checkoutService.checkout(idempotencyKey, request))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // VULNERABILITY EXPOSURE:
        // Alice's money was debited by the payment gateway:
        verify(paymentService, times(1)).processPayment(anyString(), any(BigDecimal.class), anyString(), anyString());

        // But Alice has NO tickets, booking persistence failed, and no refund was issued.
        // Reason: Checkout does NOT acquire a lease lock or Redisson seat lock across the payment boundary,
        // allowing holds to expire and collide while payment is in-flight.
    }

    // =========================================================================
    // FLAW 5: UNCOORDINATED CLUSTER SWEEPER CONTENTION ACROSS NODES
    // =========================================================================

    @Test
    @DisplayName("FLAW 5: ScheduledHoldSweeper lacks distributed locking, allowing multiple cluster pods to sweep concurrently and collide")
    void testFlaw5_UncoordinatedClusterSweeperCausesConcurrentDbContention() throws Exception {
        // Arrange
        SeatReclamationService reclamationService = mock(SeatReclamationService.class);
        ScheduledHoldSweeper sweeperPod1 = new ScheduledHoldSweeper(reclamationService);
        ScheduledHoldSweeper sweeperPod2 = new ScheduledHoldSweeper(reclamationService);

        // Pod 1 sweeps 5 seats
        when(reclamationService.sweepOrphanedHolds()).thenReturn(5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        // Simulate Pod 1 and Pod 2 triggering their 60s @Scheduled tick at the exact same millisecond
        Future<Integer> pod1Execution = pool.submit(() -> {
            latch.await();
            sweeperPod1.sweepOrphanedHolds();
            return 1;
        });

        Future<Integer> pod2Execution = pool.submit(() -> {
            latch.await();
            sweeperPod2.sweepOrphanedHolds();
            return 2;
        });

        latch.countDown();
        pod1Execution.get(2, TimeUnit.SECONDS);
        pod2Execution.get(2, TimeUnit.SECONDS);
        pool.shutdown();

        // VULNERABILITY EXPOSURE:
        // Both Pod 1 and Pod 2 called sweepOrphanedHolds() simultaneously!
        // In production, both pods issue SELECT ... WHERE status='HELD' AND hold_expires_at < now
        // and concurrently attempt saveAll() on the exact same expired rows, causing
        // row lock contention and Hibernate OptimisticLockExceptions across cluster instances.
        verify(reclamationService, times(2)).sweepOrphanedHolds();
    }

    // =========================================================================
    // FLAW 6: READ-SIDE CACHE INCOHERENCE IN SEAT MATRIX
    // =========================================================================

    @Test
    @DisplayName("FLAW 6: getSeatMatrix reads only PostgreSQL, displaying expired holds as HELD even though they are available for re-hold")
    void testFlaw6_SeatMatrixReadSideCacheIncoherence() {
        // Arrange
        SeatHoldService seatHoldService = new SeatHoldService(
                redissonClient, stringRedisTemplate, showSeatRepository, showRepository, TicketWaveMetrics.noop()
        );

        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));

        // Seat 101's hold expired 30 seconds ago in PostgreSQL, and its Redis key has expired.
        // But the 60-second sweeper has not executed yet!
        ShowSeat expiredSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(testSeat)
                .status(SeatStatus.HELD)
                .holdReference(UUID.randomUUID())
                .holdExpiresAt(Instant.now().minusSeconds(30))
                .price(BigDecimal.valueOf(250.00))
                .build();

        when(showSeatRepository.findByShowIdWithSeatDetails(1L))
                .thenReturn(List.of(expiredSeat));

        // Act: User loads seat map
        SeatMatrixResponse response = seatHoldService.getSeatMatrix(1L);

        // VULNERABILITY EXPOSURE:
        // 1. getSeatMatrix returns status = HELD to the user!
        assertThat(response.getSeats()).hasSize(1);
        assertThat(response.getSeats().get(0).getStatus()).isEqualTo(SeatStatus.HELD);

        // 2. User sees the seat as unavailable and walks away.
        // BUT in reality, acquireSeatHold has logic:
        // isExpiredZombieHold = ss.getStatus() == SeatStatus.HELD && ss.getHoldExpiresAt().isBefore(now)
        // Which would ALLOW someone to grab the seat right now!
        // getSeatMatrix has read-side cache incoherence because it does not project real-time Redis/TTL availability!
    }
}
