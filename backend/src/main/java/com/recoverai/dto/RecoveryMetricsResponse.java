package com.recoverai.dto;

import java.math.BigDecimal;

/**
 * Portfolio-level recovery metrics (Phase 12), computed entirely from
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
 * <p>
 * <b>{@code amountRemainingAtRisk}</b> = {@code max(0, totalRevenueAtRisk -
 * confirmedRecoveredRevenue)} - the portion of currently at-risk revenue
 * that has not (yet) been confirmed recovered. It never double-counts:
 * {@code totalRevenueAtRisk} already excludes resolved transactions (see
 * {@code RevenueRiskService.correctStaleResolvedRiskRows}), so this is
 * simply that figure net of whatever has since been genuinely confirmed.
 * Floored at zero rather than allowed to go negative, since a transient
 * timing gap between a webhook confirming a payment and the next risk
 * re-analysis could otherwise produce a nonsensical negative "risk".
 * <p>
 * <b>{@code distinctCustomersProcessed}</b> (Phase 14) - the count of
 * distinct customers with at least one {@code RecoveryAttempt}, i.e.
 * customers the recovery system has actually acted on, not merely
 * customers with an at-risk transaction.
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
        BigDecimal pendingConfirmationAmount,
        BigDecimal amountRemainingAtRisk,
        long transactionsRecovered,
        long transactionsEscalated,
        long transactionsStopped,
        long distinctCustomersProcessed
) {
}
