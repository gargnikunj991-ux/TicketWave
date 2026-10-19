package com.ticketwave.booking.dto;

import com.ticketwave.booking.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Confirmed booking response including issued tickets and transaction details")
public class CheckoutResponse {

    @Schema(description = "Human-readable permanent booking reference", example = "TW-BK-A1B2C3D4")
    private String bookingReference;

    @Schema(description = "Current lifecycle status of booking", example = "CONFIRMED")
    private BookingStatus status;

    @Schema(description = "Show ID", example = "1")
    private Long showId;

    @Schema(description = "Purchaser user ID", example = "usr_98124")
    private String userId;

    @Schema(description = "Gateway payment transaction identifier", example = "TXN_UPI_8A9B0C1D2E3F")
    private String paymentTxnId;

    @Schema(description = "Total settled booking amount", example = "500.00")
    private BigDecimal totalAmount;

    @Schema(description = "List of issued tickets tied to seats")
    private List<TicketDto> tickets;

    @Schema(description = "UTC timestamp when booking was committed")
    private Instant bookedAt;
}
