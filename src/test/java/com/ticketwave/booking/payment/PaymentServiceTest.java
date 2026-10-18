package com.ticketwave.booking.payment;

import com.ticketwave.infrastructure.exception.PaymentFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest {

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService();
    }

    @Test
    @DisplayName("Successfully processes payment with approved mock token")
    void testProcessPayment_Success() {
        PaymentResult result = paymentService.processPayment(
                "usr_123",
                BigDecimal.valueOf(15000.00),
                "UPI",
                "tok_mock_success_upi"
        );

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.getTransactionId()).startsWith("TXN_UPI_");
        assertThat(result.getMessage()).contains("successfully");
    }

    @Test
    @DisplayName("Throws PaymentFailedException when mock declined token is used")
    void testProcessPayment_Declined() {
        assertThatThrownBy(() -> paymentService.processPayment(
                "usr_123",
                BigDecimal.valueOf(15000.00),
                "CARD",
                "tok_mock_declined_insufficient_funds"
        ))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("Payment declined by bank");
    }

    @Test
    @DisplayName("Throws PaymentFailedException when payment token is null or blank")
    void testProcessPayment_BlankToken() {
        assertThatThrownBy(() -> paymentService.processPayment(
                "usr_123",
                BigDecimal.valueOf(5000.00),
                "CARD",
                ""
        ))
                .isInstanceOf(PaymentFailedException.class)
                .hasMessageContaining("Payment token is missing or invalid");
    }
}
