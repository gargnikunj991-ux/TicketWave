package com.ticketwave.concurrency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ticketwave.booking.BookingCheckoutService;
import com.ticketwave.booking.BookingPersistenceService;
import com.ticketwave.booking.BookingRepository;
import com.ticketwave.booking.BookingStatus;
import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.booking.dto.TicketDto;
import com.ticketwave.booking.idempotency.IdempotencyService;
import com.ticketwave.booking.payment.PaymentResult;
import com.ticketwave.booking.payment.PaymentService;
import com.ticketwave.booking.payment.PaymentStatus;
import com.ticketwave.event.Event;
import com.ticketwave.infrastructure.exception.IdempotencyConflictException;
import com.ticketwave.infrastructure.exception.SeatHoldExpiredException;
import com.ticketwave.seat.*;
import com.ticketwave.show.Show;
import com.ticketwave.show.ShowStatus;
import com.ticketwave.venue.Screen;
import com.ticketwave.venue.ScreenType;
import com.ticketwave.venue.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ⚡ Phase 6: High-Contention Multithreaded Checkout Concurrency Stress Test Suite
 *
 * Verifies financial safety, zero double-charges, and Redis idempotency gate under concurrent burst:
 *
 * 1. 50 concurrent threads checkout with identical Idempotency-Key -> Payment executed EXACTLY ONCE.
 * 2. 30 concurrent threads checkout same hold with different Idempotency-Keys -> Exactly 1 succeeds, 29 rejected.
 */
class BookingCheckoutConcurrencyStressTest {

    private IdempotencyService idempotencyService;
    private PaymentService paymentService;
    private BookingPersistenceService bookingPersistenceService;
    private BookingRepository bookingRepository;
    private ShowSeatRepository showSeatRepository;
    private StringRedisTemplate stringRedisTemplate;
    private BookingCheckoutService bookingCheckoutService;

    private final ConcurrentHashMap<String, String> redisStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ShowSeat> seatStore = new ConcurrentHashMap<>();

    private Show testShow;
    private UUID testHoldRef;
    private final String testUserId = "usr_alice";

    @BeforeEach
    void setUp() {
        redisStore.clear();
        seatStore.clear();

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Thread-safe simulated StringRedisTemplate
        stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valOps);

        when(valOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            return redisStore.putIfAbsent(key, val) == null;
        });

        when(valOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStore.get(key);
        });

        Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            redisStore.put(key, val);
            return null;
        }).when(valOps).set(anyString(), anyString(), any(Duration.class));

        when(stringRedisTemplate.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStore.remove(key) != null;
        });

        when(stringRedisTemplate.hasKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStore.containsKey(key);
        });

        idempotencyService = new IdempotencyService(stringRedisTemplate, objectMapper);

        paymentService = mock(PaymentService.class);
        bookingPersistenceService = mock(BookingPersistenceService.class);
        bookingRepository = mock(BookingRepository.class);
        showSeatRepository = mock(ShowSeatRepository.class);

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

        testHoldRef = UUID.randomUUID();

        // 2 HELD seats for testHoldRef
        Seat s1 = Seat.builder().id(101L).screen(screen).rowLabel("A").seatNumber(1).tier(SeatTier.VIP).build();
        Seat s2 = Seat.builder().id(102L).screen(screen).rowLabel("A").seatNumber(2).tier(SeatTier.VIP).build();

        ShowSeat ss1 = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(s1)
                .status(SeatStatus.HELD)
                .holdReference(testHoldRef)
                .holdExpiresAt(Instant.now().plusSeconds(600))
                .price(BigDecimal.valueOf(250.00))
                .build();

        ShowSeat ss2 = ShowSeat.builder()
                .id(2L)
                .show(testShow)
                .seat(s2)
                .status(SeatStatus.HELD)
                .holdReference(testHoldRef)
                .holdExpiresAt(Instant.now().plusSeconds(600))
                .price(BigDecimal.valueOf(250.00))
                .build();

        seatStore.put(101L, ss1);
        seatStore.put(102L, ss2);

        // Redis hold state
        redisStore.put("hold:show:1:seat:101", testHoldRef + ":" + testUserId);
        redisStore.put("hold:show:1:seat:102", testHoldRef + ":" + testUserId);

        when(showSeatRepository.findByHoldReferenceWithDetails(testHoldRef)).thenAnswer(inv -> {
            List<ShowSeat> list = new ArrayList<>();
            for (ShowSeat ss : seatStore.values()) {
                if (testHoldRef.equals(ss.getHoldReference())) {
                    list.add(ss);
                }
            }
            return list;
        });

        when(showSeatRepository.findByHoldReference(testHoldRef)).thenAnswer(inv -> {
            List<ShowSeat> list = new ArrayList<>();
            for (ShowSeat ss : seatStore.values()) {
                if (testHoldRef.equals(ss.getHoldReference())) {
                    list.add(ss);
                }
            }
            return list;
        });

        bookingCheckoutService = new BookingCheckoutService(
                idempotencyService,
                paymentService,
                bookingPersistenceService,
                bookingRepository,
                showSeatRepository,
                stringRedisTemplate,
                null
        );
    }

    @Test
    @DisplayName("Verify 50 concurrent checkouts with identical Idempotency-Key executes payment and booking EXACTLY ONCE")
    void test50ConcurrentCheckoutsIdenticalIdempotencyKey() throws Exception {
        int totalThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        String idempotencyKey = "key-idem-" + UUID.randomUUID();

        CheckoutResponse simulatedResponse = CheckoutResponse.builder()
                .bookingReference("TW-BK-CONCURRENCY-1")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId(testUserId)
                .paymentTxnId("TXN_12345")
                .totalAmount(BigDecimal.valueOf(500.00))
                .tickets(List.of(
                        TicketDto.builder().ticketSerial("TKT-001").seatId(101L).row("A").number(1).tier(SeatTier.VIP).price(BigDecimal.valueOf(250.00)).build(),
                        TicketDto.builder().ticketSerial("TKT-002").seatId(102L).row("A").number(2).tier(SeatTier.VIP).price(BigDecimal.valueOf(250.00)).build()
                ))
                .bookedAt(Instant.now())
                .build();

        // Payment returns deterministic success
        when(paymentService.processPayment(eq(testUserId), any(BigDecimal.class), eq("UPI"), anyString()))
                .thenAnswer(inv -> {
                    // Small simulated network latency
                    Thread.sleep(50);
                    return PaymentResult.builder()
                            .status(PaymentStatus.SUCCESS)
                            .transactionId("TXN_12345")
                            .message("Payment approved")
                            .build();
                });

        when(bookingPersistenceService.commitBooking(anyList(), any(CheckoutRequest.class), any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    // Mark seats as BOOKED in seatStore
                    for (ShowSeat ss : seatStore.values()) {
                        ss.setStatus(SeatStatus.BOOKED);
                        ss.setHoldReference(null);
                    }
                    return simulatedResponse;
                });

        CheckoutRequest request = CheckoutRequest.builder()
                .userId(testUserId)
                .holdReference(testHoldRef)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        AtomicInteger successOrReplayCount = new AtomicInteger(0);
        AtomicInteger inProgressConflictCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Throwable> unexpectedExceptions = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    CheckoutResponse response = bookingCheckoutService.checkout(idempotencyKey, request);
                    if (response != null && BookingStatus.CONFIRMED.equals(response.getStatus())) {
                        successOrReplayCount.incrementAndGet();
                    }
                } catch (IdempotencyConflictException e) {
                    inProgressConflictCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedExceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(unexpectedExceptions).as("Zero unexpected errors during checkout burst").isEmpty();

        // Total threads = successful original or replayed responses + in-progress 409 conflicts
        assertThat(successOrReplayCount.get() + inProgressConflictCount.get())
                .as("All 50 threads must either receive confirmed response or graceful in-progress conflict")
                .isEqualTo(totalThreads);

        assertThat(successOrReplayCount.get())
                .as("At least the original caller receives confirmed response")
                .isGreaterThanOrEqualTo(1);

        // FINANCIAL INVARIANT: Payment and Persistence MUST be executed EXACTLY ONCE
        verify(paymentService, times(1))
                .processPayment(eq(testUserId), any(BigDecimal.class), eq("UPI"), anyString());

        verify(bookingPersistenceService, times(1))
                .commitBooking(anyList(), any(CheckoutRequest.class), any(BigDecimal.class), anyString(), eq(idempotencyKey));
    }

    @Test
    @DisplayName("Verify 30 concurrent checkouts on the SAME hold with DIFFERENT Idempotency-Keys: exactly 1 succeeds, 29 fail")
    void test30ConcurrentCheckoutsDifferentIdempotencyKeys() throws Exception {
        int totalThreads = 30;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        CheckoutResponse simulatedResponse = CheckoutResponse.builder()
                .bookingReference("TW-BK-UNIQUE-1")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId(testUserId)
                .paymentTxnId("TXN_99999")
                .totalAmount(BigDecimal.valueOf(500.00))
                .tickets(Collections.emptyList())
                .bookedAt(Instant.now())
                .build();

        when(paymentService.processPayment(anyString(), any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Thread.sleep(50);
                    return PaymentResult.builder()
                            .status(PaymentStatus.SUCCESS)
                            .transactionId("TXN_99999")
                            .message("Payment approved")
                            .build();
                });

        when(bookingPersistenceService.commitBooking(anyList(), any(CheckoutRequest.class), any(BigDecimal.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    // Transition seats to BOOKED and clear hold reference atomically
                    for (ShowSeat ss : seatStore.values()) {
                        ss.setStatus(SeatStatus.BOOKED);
                        ss.setHoldReference(null);
                    }
                    return simulatedResponse;
                });

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < totalThreads; i++) {
            final String threadKey = "key-unique-" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    CheckoutRequest req = CheckoutRequest.builder()
                            .userId(testUserId)
                            .holdReference(testHoldRef)
                            .paymentMethod("UPI")
                            .paymentToken("tok_mock_success_upi")
                            .build();

                    bookingCheckoutService.checkout(threadKey, req);
                    successCount.incrementAndGet();
                } catch (SeatHoldExpiredException | IllegalStateException e) {
                    rejectedCount.incrementAndGet();
                } catch (Throwable t) {
                    // Other graceful rejections
                    rejectedCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
                .as("Exactly 1 checkout must succeed and book the hold")
                .isEqualTo(1);

        assertThat(rejectedCount.get())
                .as("The other 29 checkouts must be rejected because hold is already consumed")
                .isEqualTo(29);

        verify(bookingPersistenceService, times(1))
                .commitBooking(anyList(), any(CheckoutRequest.class), any(BigDecimal.class), anyString(), anyString());
    }
}
