package com.recoverai.dto;

import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One real, persisted {@link RecoveryAttempt} row, shaped for the transaction detail page's recovery history. */
public record RecoveryAttemptSummaryResponse(
        UUID id,
        RecoveryAction action,
        int attemptNumber,
        RecoveryAttemptStatus status,
        String provider,
        String providerReference,
        boolean simulated,
        BigDecimal amount,
        BigDecimal amountRecovered,
        PaymentConfirmationStatus paymentConfirmationStatus,
        BigDecimal confirmedAmount,
        String providerPaymentId,
        Instant confirmedAt,
        Instant executedAt
) {
    public static RecoveryAttemptSummaryResponse from(RecoveryAttempt attempt) {
        return new RecoveryAttemptSummaryResponse(
                attempt.getId(), attempt.getAction(), attempt.getAttemptNumber(), attempt.getStatus(),
                attempt.getProvider(), attempt.getProviderReference(), "mock".equals(attempt.getProvider()),
                attempt.getAmount(), attempt.getAmountRecovered(),
                attempt.getPaymentConfirmationStatus(), attempt.getConfirmedAmount(),
                attempt.getProviderPaymentId(), attempt.getConfirmedAt(), attempt.getExecutedAt());
    }
}
