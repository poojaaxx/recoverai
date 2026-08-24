package com.recoverai.dto;

import java.math.BigDecimal;

/**
 * Batch-level revenue-at-risk metrics, computed entirely from persisted
 * {@code RevenueRisk} / {@code Transaction} rows via database aggregate
 * queries - never hardcoded, never loaded fully into memory. See {@code
 * RevenueRiskService} for the precise definition of each field.
 */
public record RevenueRiskMetricsResponse(
        long totalTransactions,
        long atRiskTransactions,
        BigDecimal totalTransactionValue,
        BigDecimal totalRevenueCollected,
        BigDecimal revenueAtRisk,
        BigDecimal highRiskRevenue,
        BigDecimal criticalRiskRevenue,
        BigDecimal averageRecoveryProbability,
        BigDecimal potentiallyRecoverableRevenue
) {
}
