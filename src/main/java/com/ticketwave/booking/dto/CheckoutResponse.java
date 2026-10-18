package com.ticketwave.booking.dto;

import com.ticketwave.booking.BookingStatus;
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
public class CheckoutResponse {

    private String bookingReference;
    private BookingStatus status;
    private Long showId;
    private String userId;
    private String paymentTxnId;
    private BigDecimal totalAmount;
    private List<TicketDto> tickets;
    private Instant bookedAt;
}
