package com.ticketwave.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Checkout payload providing user identity, hold reference, and payment credentials")
public class CheckoutRequest {

    @NotBlank(message = "User ID is required")
    @Schema(description = "Unique identifier of the purchaser", example = "usr_98124")
    private String userId;

    @NotNull(message = "Hold reference is required")
    @Schema(description = "UUID hold reference issued during seat reservation", example = "a8098c1a-f86e-11da-bd1a-00112444be1e")
    private UUID holdReference;

    @NotBlank(message = "Payment method is required (e.g. UPI, CREDIT_CARD, DEBIT_CARD)")
    @Schema(description = "Payment method channel", example = "UPI")
    private String paymentMethod;

    @NotBlank(message = "Payment token is required (e.g. tok_mock_success_upi)")
    @Schema(description = "Payment simulation token (e.g. tok_mock_success_upi, tok_mock_decline)", example = "tok_mock_success_upi")
    private String paymentToken;
}
