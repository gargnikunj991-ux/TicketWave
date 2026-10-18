package com.ticketwave.booking;

import com.ticketwave.booking.dto.CheckoutRequest;
import com.ticketwave.booking.dto.CheckoutResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Tag(name = "Booking & Payment Engine", description = "Transactional checkout, payment idempotency, and booking retrieval APIs")
public class BookingController {

    private final BookingCheckoutService bookingCheckoutService;

    @PostMapping("/checkout")
    @Operation(
            summary = "Checkout & Confirm Booking",
            description = "Executes payment verification and converts a valid seat hold into a permanent booking. Protected by Redis idempotency."
    )
    @ApiResponse(responseCode = "201", description = "Booking created and confirmed successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request payload or missing idempotency header")
    @ApiResponse(responseCode = "402", description = "Simulated payment declined by bank")
    @ApiResponse(responseCode = "409", description = "Concurrent checkout with identical idempotency key already in progress")
    @ApiResponse(responseCode = "410", description = "Seat hold has expired and seats returned to inventory")
    public ResponseEntity<CheckoutResponse> checkout(
            @Parameter(description = "Client-generated unique UUID to ensure payment idempotency", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request
    ) {
        CheckoutResponse response = bookingCheckoutService.checkout(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{bookingReference}")
    @Operation(
            summary = "Get Booking Details",
            description = "Retrieves confirmed booking information including issued tickets by booking reference."
    )
    @ApiResponse(responseCode = "200", description = "Booking details retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Booking not found")
    public ResponseEntity<CheckoutResponse> getBooking(
            @PathVariable String bookingReference
    ) {
        return ResponseEntity.ok(bookingCheckoutService.getBookingByReference(bookingReference));
    }
}
