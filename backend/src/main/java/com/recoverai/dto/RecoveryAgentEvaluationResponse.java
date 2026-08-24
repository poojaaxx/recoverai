package com.recoverai.dto;

import com.recoverai.domain.RecoveryAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Full result of asking the Phase 5 AI recovery agent for a
 * recommendation and running it through the Phase 4 policy engine.
 * {@code finalAction} always reflects {@code policyDecision} - it is
 * {@code null} when {@code policyDecision.decision() == BLOCK} (no action
 * is applicable), and never equals the AI's recommended action when the
 * policy overrode it.
 */
public record RecoveryAgentEvaluationResponse(
        UUID transactionId,
        String externalTransactionId,
        AIRecommendationResponse aiRecommendation,
        RecoveryPolicyDecisionResponse policyDecision,
        RecoveryAction finalAction,
        boolean requiresHumanApproval,
        BigDecimal expectedRecoveryValue,
        UUID auditEventId,
        Instant evaluatedAt
) {
}
