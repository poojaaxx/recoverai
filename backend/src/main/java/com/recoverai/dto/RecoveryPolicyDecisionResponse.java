package com.recoverai.dto;

import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Result of evaluating one proposed {@link RecoveryAction} against one transaction. Evaluation only - no side effects. */
public record RecoveryPolicyDecisionResponse(
        UUID transactionId,
        String externalTransactionId,
        RecoveryAction action,
        PolicyDecision decision,
        boolean requiresHumanApproval,
        String reason,
        List<PolicyCheckResponse> policyChecks,
        Instant evaluatedAt
) {
}
