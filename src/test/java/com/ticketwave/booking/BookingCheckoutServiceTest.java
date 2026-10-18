package com.ticketwave.booking;

import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.booking.dto.TicketDto;
import com.ticketwave.booking.idempotency.IdempotencyService;
import com.ticketwave.booking.payment.PaymentResult;
import com.ticketwave.booking.payment.PaymentService;
import com.ticketwave.booking.payment.PaymentStatus;
import com.ticketwave.event.Event;
import com.ticketwave.infrastructure.exception.PaymentFailedException;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingCheckoutServiceTest {

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
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private BookingCheckoutService checkoutService;

    private Show testShow;
    private Seat testSeat;
    private ShowSeat heldShowSeat;
    private UUID holdReference;

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

        testSeat = Seat.builder()
                .id(101L)
                .screen(screen)
                .rowLabel("A")
                .seatNumber(1)
                .tier(SeatTier.VIP)
                .build();

        holdReference = UUID.randomUUID();

        heldShowSeat = ShowSeat.builder()
                .id(1L)
                .show(testShow)
                .seat(testSeat)
                .status(SeatStatus.HELD)
                .price(BigDecimal.valueOf(250.00))
                .holdReference(holdReference)
                .holdExpiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    @Test
    @DisplayName("Successfully processes checkout, charges payment, commits booking, and clears Redis hold")
    void testCheckout_Success() {
        String idempotencyKey = "key-test-123";
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(holdReference)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        // Idempotency: Brand new key
        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.empty());

        // Hold validation
        when(showSeatRepository.findByHoldReferenceWithDetails(holdReference)).thenReturn(List.of(heldShowSeat));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("hold:show:1:seat:101")).thenReturn(holdReference + ":usr_alice");

        // Payment
        PaymentResult paymentResult = PaymentResult.builder()
                .status(PaymentStatus.SUCCESS)
                .transactionId("TXN_UPI_12345")
                .message("Approved")
                .build();
        when(paymentService.processPayment(eq("usr_alice"), any(BigDecimal.class), eq("UPI"), eq("tok_mock_success_upi")))
                .thenReturn(paymentResult);

        // Persistence
        CheckoutResponse expectedResponse = CheckoutResponse.builder()
                .bookingReference("TW-BK-A1B2C3D4")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId("usr_alice")
                .paymentTxnId("TXN_UPI_12345")
                .totalAmount(BigDecimal.valueOf(250.00))
                .tickets(List.of(TicketDto.builder()
                        .ticketSerial("TKT-2026-001A01")
                        .seatId(101L)
                        .row("A")
                        .number(1)
                        .tier(SeatTier.VIP)
                        .price(BigDecimal.valueOf(250.00))
                        .build()))
                .bookedAt(Instant.now())
                .build();

        when(bookingPersistenceService.commitBooking(anyList(), eq(request), any(BigDecimal.class), eq("TXN_UPI_12345"), eq(idempotencyKey)))
                .thenReturn(expectedResponse);

        CheckoutResponse response = checkoutService.checkout(idempotencyKey, request);

        assertThat(response).isNotNull();
        assertThat(response.getBookingReference()).isEqualTo("TW-BK-A1B2C3D4");
        assertThat(response.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        // Verify Redis cleanup & Idempotency marked completed
        verify(stringRedisTemplate).delete("hold:show:1:seat:101");
        verify(stringRedisTemplate).delete("user_hold:usr_alice:" + holdReference);
        verify(idempotencyService).markCompleted(idempotencyKey, expectedResponse);
    }

    @Test
    @DisplayName("Replays completed checkout response directly from idempotency cache without payment or DB calls")
    void testCheckout_ReplayFromCache() {
        String idempotencyKey = "key-test-456";
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(holdReference)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        CheckoutResponse cachedResponse = CheckoutResponse.builder()
                .bookingReference("TW-BK-CACHED99")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId("usr_alice")
                .paymentTxnId("TXN_UPI_99999")
                .totalAmount(BigDecimal.valueOf(250.00))
                .build();

        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.of(cachedResponse));

        CheckoutResponse response = checkoutService.checkout(idempotencyKey, request);

        assertThat(response).isNotNull();
        assertThat(response.getBookingReference()).isEqualTo("TW-BK-CACHED99");

        // Verify downstream services are NEVER called
        verifyNoInteractions(paymentService);
        verifyNoInteractions(bookingPersistenceService);
        verifyNoInteractions(showSeatRepository);
    }

    @Test
    @DisplayName("Fails fast with SeatHoldExpiredException when DB hold has expired and releases lock")
    void testCheckout_ExpiredHoldInDb_Throws410() {
        String idempotencyKey = "key-test-789";
        heldShowSeat.setHoldExpiresAt(Instant.now().minusSeconds(10)); // Expired!

        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.empty());
        when(showSeatRepository.findByHoldReferenceWithDetails(holdReference)).thenReturn(List.of(heldShowSeat));

        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(holdReference)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        assertThatThrownBy(() -> checkoutService.checkout(idempotencyKey, request))
                .isInstanceOf(SeatHoldExpiredException.class)
                .hasMessageContaining("expired");

        // Verify idempotency lock released so user isn't stuck
        verify(idempotencyService).releaseLock(idempotencyKey);
        verifyNoInteractions(paymentService);
        verifyNoInteractions(bookingPersistenceService);
    }

    @Test
    @DisplayName("Fails with PaymentFailedException when bank declines and releases lock without committing DB")
    void testCheckout_PaymentDeclined_Throws402() {
        String idempotencyKey = "key-test-decline";
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(holdReference)
                .paymentMethod("CARD")
                .paymentToken("tok_mock_declined_insufficient_funds")
                .build();

        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.empty());
        when(showSeatRepository.findByHoldReferenceWithDetails(holdReference)).thenReturn(List.of(heldShowSeat));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("hold:show:1:seat:101")).thenReturn(holdReference + ":usr_alice");

        when(paymentService.processPayment(anyString(), any(BigDecimal.class), anyString(), anyString()))
                .thenThrow(new PaymentFailedException("Payment declined by bank"));

        assertThatThrownBy(() -> checkoutService.checkout(idempotencyKey, request))
                .isInstanceOf(PaymentFailedException.class);

        // Verify lock is released and DB is NOT committed
        verify(idempotencyService).releaseLock(idempotencyKey);
        verifyNoInteractions(bookingPersistenceService);
    }

    @Test
    @DisplayName("Fails with IllegalArgumentException when user does not own the hold")
    void testCheckout_UnauthorizedUser_ThrowsException() {
        String idempotencyKey = "key-test-idor";
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_eve_attacker") // Attacker!
                .holdReference(holdReference)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        when(idempotencyService.acquireOrGetCached(idempotencyKey)).thenReturn(Optional.empty());
        when(showSeatRepository.findByHoldReferenceWithDetails(holdReference)).thenReturn(List.of(heldShowSeat));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("hold:show:1:seat:101")).thenReturn(holdReference + ":usr_alice"); // Owned by Alice!

        assertThatThrownBy(() -> checkoutService.checkout(idempotencyKey, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not authorized");

        verify(idempotencyService).releaseLock(idempotencyKey);
    }

    @Test
    @DisplayName("Successfully retrieves booking by booking reference")
    void testGetBookingByReference_Success() {
        Booking booking = Booking.builder()
                .id(1L)
                .bookingReference("TW-BK-112233")
                .show(testShow)
                .userId("usr_alice")
                .totalAmount(BigDecimal.valueOf(250.00))
                .status(BookingStatus.CONFIRMED)
                .paymentTxnId("TXN_123")
                .createdAt(Instant.now())
                .tickets(List.of(Ticket.builder()
                        .ticketSerial("TKT-2026-001A01")
                        .seat(testSeat)
                        .price(BigDecimal.valueOf(250.00))
                        .build()))
                .build();

        when(bookingRepository.findByBookingReferenceWithTickets("TW-BK-112233"))
                .thenReturn(Optional.of(booking));

        CheckoutResponse response = checkoutService.getBookingByReference("TW-BK-112233");

        assertThat(response).isNotNull();
        assertThat(response.getBookingReference()).isEqualTo("TW-BK-112233");
        assertThat(response.getTickets()).hasSize(1);
        assertThat(response.getTickets().get(0).getTicketSerial()).isEqualTo("TKT-2026-001A01");
    }

    @Test
    @DisplayName("Throws ResourceNotFoundException when booking reference does not exist")
    void testGetBookingByReference_NotFound() {
        when(bookingRepository.findByBookingReferenceWithTickets("NON_EXISTENT"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> checkoutService.getBookingByReference("NON_EXISTENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Booking not found");
    }
}
