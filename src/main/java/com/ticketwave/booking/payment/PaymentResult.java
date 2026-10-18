package com.ticketwave.booking.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResult {

    private PaymentStatus status;
    private String transactionId;
    private String message;
}
