package com.recoverai.dto;

import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregated AI-recommendation statistics over every currently at-risk
 * transaction - recommendation counts and confidence only, never a claim
 * of money recovered (no execution happens in this phase).
 */
public record RecoveryAgentBatchResponse(
        long transactionsEvaluated,
        Map<RecoveryAction, Long> recommendationCountByAction,
        Map<PolicyDecision, Long> countByPolicyDecision,
        BigDecimal averageConfidence,
        long providerFailures,
        long malformedOutputs
) {
}
