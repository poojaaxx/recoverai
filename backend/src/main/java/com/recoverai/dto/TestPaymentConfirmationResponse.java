package com.recoverai.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response of the judge-safe demo confirmation path (P0.4):
 * {@code POST /api/demo/confirm-test-payment/{transactionId}}. This drives
 * the exact same {@code PaymentConfirmationService.processRazorpayWebhook}
 * method a real Razorpay webhook would hit - signature verification,
 * providerReference correlation, and amount/currency validation all run
 * for real - the only thing "test" about it is that the signed payload is
 * built and self-signed by this backend rather than delivered by Razorpay's
 * servers, since no live Razorpay Test Mode account is configured in this
 * environment. {@code label} is always present and always says so plainly;
 * this response must never be presented as a real customer payment.
 */
public record TestPaymentConfirmationResponse(
        String label,
        String outcome,
        String reason,
        UUID recoveryAttemptId,
        UUID transactionId,
        BigDecimal confirmedAmount,
        String confirmedCurrency
) {
    public static final String LABEL =
            "TEST/SIMULATION — signed webhook confirmation driven through the real confirmation "
                    + "pipeline; no real Razorpay payment occurred.";
}
