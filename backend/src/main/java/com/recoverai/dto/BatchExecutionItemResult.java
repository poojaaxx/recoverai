package com.recoverai.dto;

import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;

import java.math.BigDecimal;
import java.util.UUID;

/** One transaction's result within a {@link BatchExecutionResponse}. */
public record BatchExecutionItemResult(
        UUID transactionId,
        String externalTransactionId,
        BatchExecutionOutcome outcome,
        PolicyDecision policyDecision,
        RecoveryAction finalAction,
        UUID recoveryAttemptId,
        BigDecimal amount,
        String reason
) {
}
