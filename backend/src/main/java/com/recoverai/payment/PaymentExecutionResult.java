package com.recoverai.payment;

import com.recoverai.domain.RecoveryAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The structured, always-returned outcome of a {@link PaymentGateway}
 * operation - implementations never throw for an ordinary provider
 * failure, they return {@code success=false} with a {@link
 * PaymentFailureReason}.
 * <p>
 * {@code amountRecovered} represents <b>actual confirmed recovery only</b>
 * - it is {@link BigDecimal#ZERO} whenever the operation failed, and also
 * {@link BigDecimal#ZERO} for a successfully <i>created</i> {@code
 * RETRY_PAYMENT} or {@code CREATE_PAYMENT_LINK} operation, because
 * creating/sending a payment link is not itself confirmation that the
 * customer paid it - that confirmation can only come from a later
 * provider webhook/status check, which is out of scope for this phase
 * (see docs/ARCHITECTURE.md). {@code simulated=true} whenever {@code
 * provider="mock"}, so a caller can never mistake a mock result for a
 * real one.
 * <p>
 * {@code paymentLinkUrl} is the payable link a customer would actually open (Razorpay's
 * {@code short_url}) - present only for a successful {@code RazorpayPaymentGateway} payment-
 * link creation, {@code null} for every mock result and every failure. It is purely
 * informational: nothing in this system ever infers payment success or {@code
 * TransactionStatus.RECOVERED} from its presence - only a verified webhook does that (see
 * {@code com.recoverai.webhook.PaymentConfirmationService}).
 */
public record PaymentExecutionResult(
        boolean success,
        String provider,
        String providerReference,
        UUID transactionId,
        RecoveryAction action,
        BigDecimal amount,
        String currency,
        BigDecimal amountRecovered,
        boolean simulated,
        String status,
        PaymentFailureReason failureCode,
        String failureReason,
        String idempotencyKey,
        Instant executedAt,
        String paymentLinkUrl
) {
}
