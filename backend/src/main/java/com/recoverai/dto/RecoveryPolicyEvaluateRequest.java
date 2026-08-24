package com.recoverai.dto;

import com.recoverai.domain.RecoveryAction;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code POST /api/recovery-policy/evaluate/{transactionId}}. */
public record RecoveryPolicyEvaluateRequest(
        @NotNull RecoveryAction action
) {
}
