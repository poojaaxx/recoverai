package com.recoverai.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregate Phase 8 demo metrics over the five fixed scenarios. {@code
 * confirmedAmountRecovered} is summed only from real {@code
 * amountRecovered} figures (never from {@code potentialRecoveryValue},
 * {@code amountAtRisk}, or execution success) — see {@code
 * RecoveryDemoService}.
 */
public record RecoveryDemoSummaryResponse(
        int scenariosEvaluated,
        int atRiskScenarios,
        int allowedCount,
        int blockedCount,
        int escalatedCount,
        int stoppedCount,
        int executedCount,
        int gatewayCalls,
        int simulatedExecutions,
        BigDecimal totalAmountAtRisk,
        BigDecimal totalPotentialRecoveryValue,
        BigDecimal confirmedAmountRecovered,
        List<RecoveryDemoScenarioResponse> scenarios
) {
}
