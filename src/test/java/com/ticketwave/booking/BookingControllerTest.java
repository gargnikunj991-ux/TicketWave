package com.ticketwave.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.booking.dto.TicketDto;
import com.ticketwave.infrastructure.exception.GlobalExceptionHandler;
import com.ticketwave.infrastructure.exception.IdempotencyConflictException;
import com.ticketwave.infrastructure.exception.PaymentFailedException;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import com.ticketwave.infrastructure.exception.SeatHoldExpiredException;
import com.ticketwave.seat.SeatTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
@Import(GlobalExceptionHandler.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingCheckoutService bookingCheckoutService;

    @Test
    @DisplayName("POST /api/v1/bookings/checkout returns 201 Created when checkout succeeds")
    void testCheckout_Returns201() throws Exception {
        UUID holdRef = UUID.randomUUID();
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(holdRef)
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        CheckoutResponse response = CheckoutResponse.builder()
                .bookingReference("TW-BK-789123")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId("usr_alice")
                .paymentTxnId("TXN_UPI_98234")
                .totalAmount(BigDecimal.valueOf(15000.00))
                .tickets(List.of(TicketDto.builder()
                        .ticketSerial("TKT-2026-001A01")
                        .seatId(101L)
                        .row("A")
                        .number(1)
                        .tier(SeatTier.VIP)
                        .price(BigDecimal.valueOf(15000.00))
                        .build()))
                .bookedAt(Instant.now())
                .build();

        when(bookingCheckoutService.checkout(eq("idem-key-1"), any(CheckoutRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings/checkout")
                        .header("Idempotency-Key", "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingReference").value("TW-BK-789123"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(15000.00))
                .andExpect(jsonPath("$.tickets[0].ticketSerial").value("TKT-2026-001A01"));
    }

    @Test
    @DisplayName("POST /api/v1/bookings/checkout returns 400 Bad Request when Idempotency-Key header is missing")
    void testCheckout_MissingIdempotencyHeader_Returns400() throws Exception {
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(UUID.randomUUID())
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        mockMvc.perform(post("/api/v1/bookings/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/bookings/checkout returns 400 Bad Request when required fields are blank")
    void testCheckout_InvalidBody_Returns400() throws Exception {
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("") // Blank!
                .holdReference(null) // Null!
                .paymentMethod("")
                .paymentToken("")
                .build();

        mockMvc.perform(post("/api/v1/bookings/checkout")
                        .header("Idempotency-Key", "idem-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request / Validation Failed"));
    }

    @Test
    @DisplayName("POST /api/v1/bookings/checkout returns 410 Gone when seat hold has expired")
    void testCheckout_ExpiredHold_Returns410() throws Exception {
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(UUID.randomUUID())
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        when(bookingCheckoutService.checkout(eq("idem-key-3"), any(CheckoutRequest.class)))
                .thenThrow(new SeatHoldExpiredException("Seat hold has expired. Seats returned to inventory pool."));

        mockMvc.perform(post("/api/v1/bookings/checkout")
                        .header("Idempotency-Key", "idem-key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Seat Hold Expired"))
                .andExpect(jsonPath("$.status").value(410));
    }

    @Test
    @DisplayName("POST /api/v1/bookings/checkout returns 402 Payment Required when payment is declined")
    void testCheckout_PaymentDeclined_Returns402() throws Exception {
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(UUID.randomUUID())
                .paymentMethod("CARD")
                .paymentToken("tok_mock_declined_insufficient_funds")
                .build();

        when(bookingCheckoutService.checkout(eq("idem-key-4"), any(CheckoutRequest.class)))
                .thenThrow(new PaymentFailedException("Payment declined by bank: insufficient funds"));

        mockMvc.perform(post("/api/v1/bookings/checkout")
                        .header("Idempotency-Key", "idem-key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.title").value("Payment Failed"))
                .andExpect(jsonPath("$.status").value(402));
    }

    @Test
    @DisplayName("POST /api/v1/bookings/checkout returns 409 Conflict when idempotency key is IN_PROGRESS")
    void testCheckout_IdempotencyConflict_Returns409() throws Exception {
        CheckoutRequest request = CheckoutRequest.builder()
                .userId("usr_alice")
                .holdReference(UUID.randomUUID())
                .paymentMethod("UPI")
                .paymentToken("tok_mock_success_upi")
                .build();

        when(bookingCheckoutService.checkout(eq("idem-key-5"), any(CheckoutRequest.class)))
                .thenThrow(new IdempotencyConflictException("Checkout request with idempotency key is currently in progress."));

        mockMvc.perform(post("/api/v1/bookings/checkout")
                        .header("Idempotency-Key", "idem-key-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET /api/v1/bookings/{bookingReference} returns 200 OK and booking details")
    void testGetBooking_Returns200() throws Exception {
        CheckoutResponse response = CheckoutResponse.builder()
                .bookingReference("TW-BK-789123")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId("usr_alice")
                .paymentTxnId("TXN_12345")
                .totalAmount(BigDecimal.valueOf(500.00))
                .tickets(List.of())
                .bookedAt(Instant.now())
                .build();

        when(bookingCheckoutService.getBookingByReference("TW-BK-789123"))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/bookings/TW-BK-789123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingReference").value("TW-BK-789123"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("GET /api/v1/bookings/{bookingReference} returns 404 Not Found when reference does not exist")
    void testGetBooking_NotFound_Returns404() throws Exception {
        when(bookingCheckoutService.getBookingByReference("TW-BK-NONEXISTENT"))
                .thenThrow(new ResourceNotFoundException("Booking not found with reference: TW-BK-NONEXISTENT"));

        mockMvc.perform(get("/api/v1/bookings/TW-BK-NONEXISTENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }
}
