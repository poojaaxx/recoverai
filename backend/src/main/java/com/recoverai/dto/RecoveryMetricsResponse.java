package com.recoverai.dto;

import java.math.BigDecimal;

/**
 * Portfolio-level recovery metrics (Phase 11), computed entirely from
 * persisted rows via database aggregate queries - never hardcoded. See
 * {@code com.recoverai.execution.RecoveryMetricsService}.
 * <p>
 * <b>{@code confirmedRecoveredRevenue} is the only revenue figure this
 * system will ever report as actually recovered</b> - it is summed only
 * from {@code RecoveryAttempt} rows a verified provider webhook confirmed
 * (see {@code PaymentConfirmationService}), never from {@code
 * potentiallyRecoverableRevenue}, execution success, or any other
 * optimistic figure. With no confirmed payments (true for every
 * environment today unless a real Razorpay Test Mode webhook has actually
 * been received), it is honestly {@code 0.00}.
 */
public record RecoveryMetricsResponse(
        BigDecimal totalRevenueAtRisk,
        BigDecimal potentiallyRecoverableRevenue,
        long recoveryAttempts,
        long successfulExecutionCount,
        long confirmedRecoveryCount,
        BigDecimal confirmedRecoveredRevenue,
        BigDecimal recoveryRate,
        BigDecimal executionSuccessRate,
        BigDecimal confirmationRate,
        BigDecimal pendingConfirmationAmount
) {
}
