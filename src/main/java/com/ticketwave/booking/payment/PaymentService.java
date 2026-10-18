package com.ticketwave.booking.payment;

import com.ticketwave.infrastructure.exception.PaymentFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@Slf4j
public class PaymentService {

    /**
     * Simulates payment gateway execution (e.g., Stripe, UPI, Razorpay).
     *
     * In a production environment, this would call an external payment gateway HTTP API.
     * We simulate deterministic outcomes based on mock tokens:
     * - "tok_mock_fail_*" or "tok_mock_declined_*": Simulates bank decline.
     * - "tok_mock_success_*" or any standard mock token: Simulates successful debit.
     */
    public PaymentResult processPayment(String userId, BigDecimal amount, String paymentMethod, String paymentToken) {
        log.info("Processing simulated payment for user [{}] of amount [{}] via [{}] with token [{}]",
                userId, amount, paymentMethod, paymentToken);

        if (paymentToken == null || paymentToken.isBlank()) {
            throw new PaymentFailedException("Payment token is missing or invalid");
        }

        if (paymentToken.startsWith("tok_mock_fail") || paymentToken.startsWith("tok_mock_declined")) {
            log.warn("Payment declined for user [{}] with token [{}]", userId, paymentToken);
            throw new PaymentFailedException("Payment declined by bank: insufficient funds or invalid payment token");
        }

        String txnId = "TXN_" + paymentMethod.toUpperCase() + "_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        log.info("Payment approved. Assigned transaction ID: [{}]", txnId);

        return PaymentResult.builder()
                .status(PaymentStatus.SUCCESS)
                .transactionId(txnId)
                .message("Payment authorized and captured successfully")
                .build();
    }
}
