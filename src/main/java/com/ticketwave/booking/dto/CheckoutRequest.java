package com.ticketwave.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {

    @NotBlank(message = "User ID is required")
    private String userId;

    @NotNull(message = "Hold reference is required")
    private UUID holdReference;

    @NotBlank(message = "Payment method is required (e.g. UPI, CREDIT_CARD, DEBIT_CARD)")
    private String paymentMethod;

    @NotBlank(message = "Payment token is required (e.g. tok_mock_success_upi)")
    private String paymentToken;
}
