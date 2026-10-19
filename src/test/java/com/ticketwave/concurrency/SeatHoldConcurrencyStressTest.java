package com.ticketwave.concurrency;

import com.ticketwave.event.Event;
import com.ticketwave.infrastructure.exception.LockAcquisitionException;
import com.ticketwave.infrastructure.exception.SeatHoldConflictException;
import com.ticketwave.seat.*;
import com.ticketwave.seat.dto.SeatHoldRequest;
import com.ticketwave.seat.dto.SeatHoldResponse;
import com.ticketwave.show.Show;
import com.ticketwave.show.ShowRepository;
import com.ticketwave.show.ShowStatus;
import com.ticketwave.venue.Screen;
import com.ticketwave.venue.ScreenType;
import com.ticketwave.venue.Venue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import org.mockito.Mockito;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⚡ Phase 6: High-Contention Multithreaded Concurrency Stress Test Suite
 *
 * Verifies the correctness, thread safety, and deadlock freedom of the seat hold engine
 * under high-concurrency burst conditions using ExecutorService and CountDownLatch:
 *
 * 1. 100 concurrent threads competing for the exact same seat -> exactly 1 win, 99 graceful rejections, 0 overbookings.
 * 2. 50 concurrent threads with circular / inverted seat selections -> zero deadlocks, deterministic lock ordering.
 * 3. 50 concurrent threads competing for 5 distinct seat pairs across 10 seats -> exactly 5 wins, 45 graceful rejections.
 */
class SeatHoldConcurrencyStressTest {

    private RedissonClient redissonClient;
    private StringRedisTemplate stringRedisTemplate;
    private ShowSeatRepository showSeatRepository;
    private ShowRepository showRepository;
    private SeatHoldService seatHoldService;

    // In-memory thread-safe state stores
    private final ConcurrentHashMap<String, ReentrantLock> lockMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> redisStringStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> redisSetStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ShowSeat> seatStore = new ConcurrentHashMap<>();

    private Show testShow;

    @BeforeEach
    void setUp() {
        lockMap.clear();
        redisStringStore.clear();
        redisSetStore.clear();
        seatStore.clear();

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

        // 1. Thread-safe simulated RedissonClient using Java reflection Proxy
        redissonClient = (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("getLock".equals(methodName)) {
                        String lockName = (String) args[0];
                        ReentrantLock realLock = lockMap.computeIfAbsent(lockName, k -> new ReentrantLock(true));
                        return createSimulatedRLock(realLock);
                    } else if ("getMultiLock".equals(methodName)) {
                        RLock[] locks;
                        if (args[0] instanceof RLock[]) {
                            locks = (RLock[]) args[0];
                        } else {
                            locks = Arrays.copyOf(args, args.length, RLock[].class);
                        }
                        return createSimulatedMultiLock(locks);
                    }
                    return null;
                }
        );

        // 2. Thread-safe simulated StringRedisTemplate
        stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valOps = mock(ValueOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);

        when(stringRedisTemplate.hasKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStringStore.containsKey(key) || redisSetStore.containsKey(key);
        });

        when(stringRedisTemplate.delete(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            boolean removed = redisStringStore.remove(key) != null || redisSetStore.remove(key) != null;
            return removed;
        });

        Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            String val = inv.getArgument(1);
            redisStringStore.put(key, val);
            return null;
        }).when(valOps).set(anyString(), anyString(), any(Duration.class));

        when(valOps.get(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisStringStore.get(key);
        });

        when(setOps.add(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object[] args = inv.getArguments();
            Set<String> set = redisSetStore.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
            long added = 0;
            for (int i = 1; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof String[]) {
                    for (String s : (String[]) a) {
                        set.add(s);
                        added++;
                    }
                } else if (a != null) {
                    set.add(a.toString());
                    added++;
                }
            }
            return added;
        });

        // 3. Thread-safe simulated ShowRepository & ShowSeatRepository
        showRepository = mock(ShowRepository.class);
        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));

        showSeatRepository = mock(ShowSeatRepository.class);
        when(showSeatRepository.findByShowIdAndSeatIdIn(eq(1L), anyList())).thenAnswer(inv -> {
            List<Long> seatIds = inv.getArgument(1);
            List<ShowSeat> result = new ArrayList<>();
            for (Long sid : seatIds) {
                ShowSeat ss = seatStore.get(sid);
                if (ss != null) {
                    result.add(ss);
                }
            }
            return result;
        });

        when(showSeatRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<ShowSeat> entities = inv.getArgument(0);
            for (ShowSeat ss : entities) {
                seatStore.put(ss.getSeat().getId(), ss);
            }
            return entities;
        });

        // Initialize Seat 101 to 110 in AVAILABLE state
        for (long sid = 101L; sid <= 110L; sid++) {
            Seat seat = Seat.builder()
                    .id(sid)
                    .screen(screen)
                    .rowLabel("A")
                    .seatNumber((int) (sid - 100))
                    .tier(SeatTier.VIP)
                    .build();

            ShowSeat showSeat = ShowSeat.builder()
                    .id(sid)
                    .show(testShow)
                    .seat(seat)
                    .status(SeatStatus.AVAILABLE)
                    .price(BigDecimal.valueOf(250.00))
                    .build();

            seatStore.put(sid, showSeat);
        }

        seatHoldService = new SeatHoldService(redissonClient, stringRedisTemplate, showSeatRepository, showRepository, null);
    }

    @Test
    @DisplayName("Verify 100 concurrent threads competing for 1 seat results in exactly 1 hold and 99 conflicts")
    void test100ConcurrentThreadsCompetingForSingleSeat() throws Exception {
        int totalThreads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger lockTimeoutCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Throwable> unexpectedExceptions = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<SeatHoldResponse> successfulHolds = new ConcurrentLinkedQueue<>();

        Long targetSeatId = 101L;

        for (int i = 0; i < totalThreads; i++) {
            final String userId = "usr_fan_" + i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // All 100 threads fire simultaneously
                    SeatHoldRequest request = SeatHoldRequest.builder()
                            .userId(userId)
                            .seatIds(List.of(targetSeatId))
                            .build();

                    SeatHoldResponse response = seatHoldService.acquireSeatHold(1L, request);
                    successCount.incrementAndGet();
                    successfulHolds.add(response);
                } catch (SeatHoldConflictException e) {
                    conflictCount.incrementAndGet();
                } catch (LockAcquisitionException e) {
                    lockTimeoutCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedExceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // BANG! Fire all threads
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(finished).as("All 100 threads must complete within timeout").isTrue();
        assertThat(unexpectedExceptions).as("Zero unexpected errors during concurrency burst").isEmpty();

        // STRICT CONCURRENCY INVARIANTS:
        assertThat(successCount.get())
                .as("Strictly exactly 1 thread must acquire the seat hold")
                .isEqualTo(1);

        assertThat(conflictCount.get() + lockTimeoutCount.get())
                .as("The remaining 99 threads must be gracefully rejected with 409 Conflict")
                .isEqualTo(99);

        // State Machine & Database Invariants:
        ShowSeat winningSeat = seatStore.get(targetSeatId);
        assertThat(winningSeat.getStatus())
                .as("Seat must be marked HELD in database")
                .isEqualTo(SeatStatus.HELD);

        SeatHoldResponse winningResponse = successfulHolds.peek();
        assertThat(winningResponse).isNotNull();
        assertThat(winningSeat.getHoldReference())
                .as("Database hold reference must match the winning response")
                .isEqualTo(winningResponse.getHoldReference());

        // Redis Invariant:
        String holdKey = "hold:show:1:seat:" + targetSeatId;
        assertThat(redisStringStore.get(holdKey))
                .as("Redis hold key must contain winner reference and userId")
                .isEqualTo(winningResponse.getHoldReference() + ":" + winningResponse.getUserId());
    }

    @Test
    @DisplayName("Verify deadlock freedom when 50 concurrent threads request overlapping seats in inverted order")
    void testDeadlockFreedomWithInvertedMultiSeatSelections() throws Exception {
        int totalThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

        // Group A requests [101, 102], Group B requests [102, 101] (inverted order)
        for (int i = 0; i < totalThreads; i++) {
            final String userId = "usr_order_" + i;
            final List<Long> requestedSeats = (i % 2 == 0)
                    ? List.of(101L, 102L)
                    : List.of(102L, 101L);

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    SeatHoldRequest request = SeatHoldRequest.builder()
                            .userId(userId)
                            .seatIds(requestedSeats)
                            .build();

                    seatHoldService.acquireSeatHold(1L, request);
                    successCount.incrementAndGet();
                } catch (SeatHoldConflictException | LockAcquisitionException e) {
                    rejectedCount.incrementAndGet();
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completedNoDeadlock = doneLatch.await(10, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(completedNoDeadlock)
                .as("Deadlock freedom: Natural ordering of seat locks prevents circular-wait hang")
                .isTrue();

        assertThat(exceptions).isEmpty();
        assertThat(successCount.get()).as("Exactly 1 buyer acquires both seats [101, 102]").isEqualTo(1);
        assertThat(rejectedCount.get()).as("All other 49 contenders are gracefully rejected").isEqualTo(49);

        assertThat(seatStore.get(101L).getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seatStore.get(102L).getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("Verify high-throughput disjoint competition: 50 threads competing for 5 pairs across 10 seats")
    void testDisjointHighThroughputSeatContention() throws Exception {
        int totalThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch readyLatch = new CountDownLatch(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // 5 distinct pairs: [101,102], [103,104], [105,106], [107,108], [109,110]
        // 10 threads compete for each pair
        for (int i = 0; i < totalThreads; i++) {
            int pairIndex = i % 5;
            long s1 = 101L + (pairIndex * 2L);
            long s2 = s1 + 1L;
            final List<Long> seats = List.of(s1, s2);
            final String userId = "usr_pair_" + i;

            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    SeatHoldRequest request = SeatHoldRequest.builder()
                            .userId(userId)
                            .seatIds(seats)
                            .build();

                    seatHoldService.acquireSeatHold(1L, request);
                    successCount.incrementAndGet();
                } catch (SeatHoldConflictException | LockAcquisitionException e) {
                    rejectedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly 5 pairs = exactly 5 winners, 45 losers
        assertThat(successCount.get()).as("All 5 distinct pairs should have exactly 1 winner each").isEqualTo(5);
        assertThat(rejectedCount.get()).as("Remaining 45 contenders rejected").isEqualTo(45);

        // Every single seat 101..110 must be HELD (zero overbooked, zero orphaned)
        for (long sid = 101L; sid <= 110L; sid++) {
            assertThat(seatStore.get(sid).getStatus()).isEqualTo(SeatStatus.HELD);
        }
    }

    // Helper: Wrap java.util.concurrent.locks.ReentrantLock into Redisson RLock interface using Proxy
    private RLock createSimulatedRLock(ReentrantLock realLock) {
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("tryLock".equals(methodName)) {
                        if (args != null && args.length == 3) {
                            long waitTime = (Long) args[0];
                            TimeUnit unit = (TimeUnit) args[2];
                            return realLock.tryLock(waitTime, unit);
                        }
                        return realLock.tryLock();
                    } else if ("unlock".equals(methodName)) {
                        if (realLock.isHeldByCurrentThread()) {
                            realLock.unlock();
                        }
                        return null;
                    } else if ("isHeldByCurrentThread".equals(methodName)) {
                        return realLock.isHeldByCurrentThread();
                    }
                    return null;
                }
        );
    }

    // Helper: Wrap array of RLocks into Redisson MultiLock semantics using Proxy
    private RLock createSimulatedMultiLock(RLock[] locks) {
        return (RLock) Proxy.newProxyInstance(
                RLock.class.getClassLoader(),
                new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("tryLock".equals(methodName)) {
                        long waitTime = (Long) args[0];
                        TimeUnit unit = (TimeUnit) args[2];
                        List<RLock> acquiredLocks = new ArrayList<>();
                        for (RLock lock : locks) {
                            boolean ok = lock.tryLock(waitTime, 5000, unit);
                            if (ok) {
                                acquiredLocks.add(lock);
                            } else {
                                for (RLock acq : acquiredLocks) {
                                    if (acq.isHeldByCurrentThread()) {
                                        acq.unlock();
                                    }
                                }
                                return false;
                            }
                        }
                        return true;
                    } else if ("unlock".equals(methodName)) {
                        for (RLock lock : locks) {
                            if (lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                        }
                        return null;
                    } else if ("isHeldByCurrentThread".equals(methodName)) {
                        for (RLock lock : locks) {
                            if (!lock.isHeldByCurrentThread()) {
                                return false;
                            }
                        }
                        return true;
                    }
                    return null;
                }
        );
    }
}
