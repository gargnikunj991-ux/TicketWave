package com.ticketwave.seat;

import com.ticketwave.event.Event;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import com.ticketwave.seat.dto.HoldReleaseRequest;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ⚠️ SeatHoldFailureModesTest
 *
 * This test suite captures edge cases and failure modes where the current
 * implementation breaks or fails to meet production standards:
 *
 * 1. "Zombie Hold" Bug: Expired seat holds in PostgreSQL block new bookings when Redis key has expired.
 * 2. IDOR / Insecure Direct Object Reference: Unauthorized users can release other users' holds.
 * 3. Cross-Show Leak: Releasing a hold under the wrong show ID modifies foreign show seats.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Disabled("Illustrative demonstration of Phase 3 failure modes addressed in Phase 4 and 5")
class SeatHoldFailureModesTest {

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
    private RLock mockLock;

    @Mock
    private RLock mockMultiLock;

    @InjectMocks
    private SeatHoldService seatHoldService;

    private Show testShow;
    private Show foreignShow;
    private Seat seat1;
    private ShowSeat expiredShowSeat;

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

        foreignShow = Show.builder()
                .id(2L)
                .event(event)
                .screen(screen)
                .startTime(Instant.now().plusSeconds(14400))
                .endTime(Instant.now().plusSeconds(18000))
                .status(ShowStatus.ACTIVE)
                .build();

        seat1 = Seat.builder().id(101L).screen(screen).rowLabel("A").seatNumber(1).tier(SeatTier.VIP).build();

        // Expired hold: HELD status in DB, but expiration was 5 minutes ago!
        expiredShowSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(seat1)
                .status(SeatStatus.HELD)
                .holdReference(UUID.randomUUID())
                .holdExpiresAt(Instant.now().minusSeconds(300))
                .price(BigDecimal.valueOf(250.00))
                .build();
    }

    /**
     * ❌ FAILURE MODE 1: THE "ZOMBIE HOLD" BUG
     *
     * Scenario:
     * User 1 held seat 101, but their 10 minutes expired.
     * The Redis key has expired (stringRedisTemplate.hasKey returns false).
     * Now, User 2 tries to hold seat 101.
     *
     * Expected:
     * Since the hold expired, User 2 should be able to acquire the seat!
     *
     * What actually happens:
     * SeatHoldService checks ss.getStatus() != SeatStatus.AVAILABLE and throws SeatHoldConflictException.
     * The seat is permanently stuck in HELD state in the DB!
     */
    @Test
    @DisplayName("FAILING TEST 1: Expired hold in DB blocks new buyer because TTL expiration hasn't swept the DB")
    void testAcquireSeatHold_ExpiredHoldBlocksNewBuyer_FailureMode() throws Exception {
        when(showRepository.findById(1L)).thenReturn(Optional.of(testShow));
        when(redissonClient.getLock(anyString())).thenReturn(mockLock);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(mockMultiLock);
        when(mockMultiLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(mockMultiLock.isHeldByCurrentThread()).thenReturn(true);

        // Redis key has expired!
        when(stringRedisTemplate.hasKey("hold:show:1:seat:101")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        // DB still has expiredShowSeat with status = HELD
        when(showSeatRepository.findByShowIdAndSeatIdIn(eq(1L), anyList()))
                .thenReturn(List.of(expiredShowSeat));

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_bob_new_buyer")
                .seatIds(List.of(101L))
                .build();

        // EXPECTATION: Since the hold expired, Bob should successfully acquire the seat.
        // ACTUAL RESULT: SeatHoldService throws SeatHoldConflictException because status != AVAILABLE!
        SeatHoldResponse response = seatHoldService.acquireSeatHold(1L, request);

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo("usr_bob_new_buyer");
    }

    /**
     * ❌ FAILURE MODE 2: INSECURE DIRECT OBJECT REFERENCE (IDOR) IN HOLD RELEASE
     *
     * Scenario:
     * User Alice holds a seat.
     * Attacker Eve knows or guesses the holdReference UUID.
     * Eve sends a release request with userId="usr_eve_attacker" and Alice's holdReference.
     *
     * Expected:
     * System should reject the release with an exception (SecurityException / AccessDeniedException)
     * because Eve is not the owner of this hold.
     *
     * What actually happens:
     * SeatHoldService does NOT verify that request.getUserId() matches the user who acquired the hold!
     * It happily releases Alice's seats, allowing an attacker to cancel anyone's seat hold.
     */
    @Test
    @DisplayName("FAILING TEST 2: Attacker Eve releases Alice's hold because ownership is not verified")
    void testReleaseSeatHold_UnauthorizedUserCanReleaseHold_FailureMode() {
        UUID aliceHoldRef = UUID.randomUUID();
        ShowSeat aliceSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(seat1)
                .status(SeatStatus.HELD)
                .holdReference(aliceHoldRef)
                .build();

        when(showSeatRepository.findByHoldReference(aliceHoldRef)).thenReturn(List.of(aliceSeat));

        HoldReleaseRequest eveRequest = HoldReleaseRequest.builder()
                .userId("usr_eve_attacker") // Attacker userId, NOT Alice
                .holdReference(aliceHoldRef)
                .build();

        // EXPECTATION: System must reject release if request.userId does not match the hold owner!
        // ACTUAL RESULT: SeatHoldService releases the hold without checking who owns it!
        assertThatThrownBy(() -> seatHoldService.releaseSeatHold(1L, eveRequest))
                .as("Should reject unauthorized hold release attempt")
                .isInstanceOf(SecurityException.class);
    }

    /**
     * ❌ FAILURE MODE 3: CROSS-SHOW HOLD RELEASE
     *
     * Scenario:
     * User Alice holds a seat for Show 1.
     * A release request is sent to Show 2 (/api/v1/shows/2/release) with Alice's holdReference.
     *
     * Expected:
     * System should reject the release because the hold does not belong to Show 2.
     *
     * What actually happens:
     * SeatHoldService.releaseSeatHold queries findByHoldReference without checking showId,
     * modifies Show 1's seats, and attempts to delete Redis keys for Show 2.
     */
    @Test
    @DisplayName("FAILING TEST 3: Releasing hold under wrong showId modifies foreign show seats")
    void testReleaseSeatHold_WrongShowIdCausesCrossShowStateCorruption_FailureMode() {
        UUID aliceHoldRef = UUID.randomUUID();
        ShowSeat show1Seat = ShowSeat.builder()
                .id(1L)
                .show(testShow) // Belongs to Show 1
                .seat(seat1)
                .status(SeatStatus.HELD)
                .holdReference(aliceHoldRef)
                .build();

        when(showSeatRepository.findByHoldReference(aliceHoldRef)).thenReturn(List.of(show1Seat));

        HoldReleaseRequest request = HoldReleaseRequest.builder()
                .userId("usr_alice")
                .holdReference(aliceHoldRef)
                .build();

        // EXPECTATION: Calling release on Show 2 (foreignShow) should fail with ResourceNotFoundException
        // ACTUAL RESULT: SeatHoldService modifies Show 1's seats anyway!
        assertThatThrownBy(() -> seatHoldService.releaseSeatHold(foreignShow.getId(), request))
                .as("Should reject releasing hold under mismatched showId")
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not belong to show");
    }
}
