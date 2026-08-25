package com.recoverai.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The general-purpose transaction dashboard's detail view (Phase 13) - one
 * fetch bundling everything the spec asks for: transaction, customer
 * history, revenue risk (nullable - "not analyzed" if absent), recovery
 * attempt history, and the full audit timeline. Mirrors the bundling
 * {@code RecoveryDemoScenarioResponse} already does for the curated demo
 * scenarios, generalized to any transaction. No new risk/AI/policy/payment
 * decision logic - every field here is a real, persisted fact.
 */
public record TransactionFullDetailResponse(
        TransactionDetailResponse transaction,
        int customerSuccessfulPaymentCount,
        int customerFailedPaymentCount,
        BigDecimal customerTotalHistoricalValue,
        RevenueRiskResponse risk,
        List<RecoveryAttemptSummaryResponse> recoveryAttempts,
        List<AuditTimelineEntryResponse> auditTimeline
) {
}
