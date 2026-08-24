package com.recoverai.payment;

import com.recoverai.domain.RecoveryAction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An already-authorized payment operation for {@link PaymentGateway} to
 * execute. "Already-authorized" is load-bearing: nothing in this package
 * decides whether {@code action} is safe to run - that decision (retry
 * limits, amount limits, duplicate-action protection, escalation,
 * stopping rules) belongs entirely to {@code
 * com.recoverai.policy.RecoveryPolicyService} and must have already
 * happened before a caller builds one of these.
 * <p>
 * Only {@link RecoveryAction#RETRY_PAYMENT} and {@link
 * RecoveryAction#CREATE_PAYMENT_LINK} are gateway-executable - {@code
 * SEND_RECOVERY_REMINDER}, {@code ESCALATE}, and {@code STOP} are
 * workflow/communication/policy actions, not payment-gateway operations,
 * and {@link PaymentGateway} implementations reject them with a
 * structured {@link PaymentFailureReason#INVALID_REQUEST} result rather
 * than attempting a call.
 */
public record PaymentExecutionRequest(
        UUID transactionId,
        String externalTransactionId,
        RecoveryAction action,
        BigDecimal amount,
        String currency,
        String idempotencyKey
) {
}
