package com.ticketwave.seat;

import com.ticketwave.event.Event;
import com.ticketwave.infrastructure.exception.LockAcquisitionException;
import com.ticketwave.infrastructure.exception.SeatHoldConflictException;
import com.ticketwave.seat.dto.*;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeatHoldServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ShowSeatRepository showSeatRepository;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private RLock mockLock1;

    @Mock
    private RLock mockLock2;

    @Mock
    private RLock mockMultiLock;

    @InjectMocks
    private SeatHoldService seatHoldService;

    private Show testShow;
    private Seat seat1;
    private Seat seat2;
    private ShowSeat showSeat1;
    private ShowSeat showSeat2;

    @BeforeEach
    void setUp() {
        Venue venue = Venue.builder().id(1L).name("Madison Square Garden").build();
        Screen screen = Screen.builder().id(1L).venue(venue).name("Main Arena").screenType(ScreenType.IMAX).build();
        Event event = Event.builder().id(1L).title("Coldplay Tour").build();

        testShow = Show.builder()
                .id(1L)
                .event(event)
                .screen(screen)
                .startTime(Instant.now().plusSeconds(3600))
                .endTime(Instant.now().plusSeconds(7200))
                .status(ShowStatus.ACTIVE)
                .build();

        seat1 = Seat.builder().id(101L).screen(screen).rowLabel("A").seatNumber(1).tier(SeatTier.VIP).build();
        seat2 = Seat.builder().id(102L).screen(screen).rowLabel("A").seatNumber(2).tier(SeatTier.VIP).build();

        showSeat1 = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(seat1)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(250.00))
                .build();

        showSeat2 = ShowSeat.builder()
                .id(2L)
                .show(testShow)
                .seat(seat2)
                .status(SeatStatus.AVAILABLE)
                .price(BigDecimal.valueOf(250.00))
                .build();
    }

    @Test
    @DisplayName("Successfully acquire seat hold with Redisson distributed locking and 10-minute Redis TTL")
    void testAcquireSeatHold_Success() throws Exception {
        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));
        when(redissonClient.getLock("lock:show:1:seat:101")).thenReturn(mockLock1);
        when(redissonClient.getLock("lock:show:1:seat:102")).thenReturn(mockLock2);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(mockMultiLock);
        when(mockMultiLock.tryLock(eq(2000L), eq(5000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(mockMultiLock.isHeldByCurrentThread()).thenReturn(true);

        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        when(showSeatRepository.findByShowIdAndSeatIdIn(eq(1L), anyList()))
                .thenReturn(List.of(showSeat1, showSeat2));

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_alice")
                .seatIds(List.of(102L, 101L)) // Intentionally unsorted to verify deterministic sorting
                .build();

        SeatHoldResponse response = seatHoldService.acquireSeatHold(1L, request);

        assertThat(response).isNotNull();
        assertThat(response.getHoldReference()).isNotNull();
        assertThat(response.getUserId()).isEqualTo("usr_alice");
        assertThat(response.getSeatIds()).containsExactly(101L, 102L); // Ascending sorted
        assertThat(response.getTotalHoldAmount()).isEqualByComparingTo(BigDecimal.valueOf(500.00));
        assertThat(response.getTtlSeconds()).isEqualTo(600L);

        // Verify entities updated in DB
        assertThat(showSeat1.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(showSeat2.getStatus()).isEqualTo(SeatStatus.HELD);
        verify(showSeatRepository).saveAll(anyList());

        // Verify Redis holds set
        verify(valueOperations).set(eq("hold:show:1:seat:101"), anyString(), eq(Duration.ofSeconds(600)));
        verify(valueOperations).set(eq("hold:show:1:seat:102"), anyString(), eq(Duration.ofSeconds(600)));

        // Verify locks unlocked
        verify(mockMultiLock).unlock();
    }

    @Test
    @DisplayName("Fail fast when seat is already held in Redis (returns 409 conflict)")
    void testAcquireSeatHold_FailsFastWhenHeldInRedis() throws Exception {
        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));
        when(redissonClient.getLock(anyString())).thenReturn(mockLock1);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(mockMultiLock);
        when(mockMultiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(mockMultiLock.isHeldByCurrentThread()).thenReturn(true);

        // Simulate seat 101 is already held in Redis by another user
        when(stringRedisTemplate.hasKey("hold:show:1:seat:101")).thenReturn(true);

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_bob")
                .seatIds(List.of(101L))
                .build();

        assertThatThrownBy(() -> seatHoldService.acquireSeatHold(1L, request))
                .isInstanceOf(SeatHoldConflictException.class)
                .hasMessageContaining("Seat 101 is currently held by another user");

        verify(showSeatRepository, never()).saveAll(anyList());
        verify(mockMultiLock).unlock();
    }

    @Test
    @DisplayName("Throws LockAcquisitionException when Redisson lock acquisition times out under contention")
    void testAcquireSeatHold_LockTimeoutThrowsException() throws Exception {
        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));
        when(redissonClient.getLock(anyString())).thenReturn(mockLock1);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(mockMultiLock);
        when(mockMultiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_charlie")
                .seatIds(List.of(101L))
                .build();

        assertThatThrownBy(() -> seatHoldService.acquireSeatHold(1L, request))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Unable to acquire lock on requested seats");
    }

    @Test
    @DisplayName("Successfully release voluntary seat hold")
    void testReleaseSeatHold_Success() {
        UUID holdRef = UUID.randomUUID();
        showSeat1.setStatus(SeatStatus.HELD);
        showSeat1.setHoldReference(holdRef);

        when(showSeatRepository.findByHoldReference(holdRef)).thenReturn(List.of(showSeat1));
        when(stringRedisTemplate.hasKey("user_hold:usr_alice:" + holdRef)).thenReturn(true);

        HoldReleaseRequest request = HoldReleaseRequest.builder()
                .userId("usr_alice")
                .holdReference(holdRef)
                .build();

        HoldReleaseResponse response = seatHoldService.releaseSeatHold(1L, request);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(showSeat1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(showSeat1.getHoldReference()).isNull();

        verify(stringRedisTemplate).delete("hold:show:1:seat:101");
        verify(showSeatRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("Get real-time seat matrix for a show")
    void testGetSeatMatrix_Success() {
        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));
        when(showSeatRepository.findByShowIdWithSeatDetails(1L)).thenReturn(List.of(showSeat1, showSeat2));

        SeatMatrixResponse response = seatHoldService.getSeatMatrix(1L);

        assertThat(response.getShowId()).isEqualTo(1L);
        assertThat(response.getEventName()).isEqualTo("Coldplay Tour");
        assertThat(response.getSeats()).hasSize(2);
        assertThat(response.getSeats().get(0).getSeatId()).isEqualTo(101L);
        assertThat(response.getSeats().get(0).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }
}
