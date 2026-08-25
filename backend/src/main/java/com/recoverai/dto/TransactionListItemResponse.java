package com.recoverai.dto;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the general-purpose transaction dashboard list (Phase 13) -
 * real transaction data plus whatever risk/recovery facts already exist
 * for it, never fabricated. The risk fields are {@code null} for a
 * transaction nobody has analyzed yet ({@code amountAtRisk} etc. are only
 * ever set by a real {@code RevenueRiskService} call) - the frontend shows
 * "Not analyzed" rather than inventing a score. {@code latestRecovery*}
 * fields are {@code null} when no {@code RecoveryAttempt} exists yet for
 * this transaction.
 */
public record TransactionListItemResponse(
        UUID id,
        String externalTransactionId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        PaymentMethod paymentMethod,
        String failureCode,
        int attemptCount,
        Instant createdAt,

        BigDecimal riskScore,
        RiskLevel riskLevel,
        BigDecimal recoveryProbability,
        BigDecimal amountAtRisk,

        RecoveryAction latestRecoveryAction,
        RecoveryAttemptStatus latestRecoveryStatus,
        Instant latestRecoveryAt
) {
}
