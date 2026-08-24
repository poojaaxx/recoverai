package com.recoverai.agent;

import com.recoverai.domain.InterventionType;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.Urgency;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A structured, machine-readable recovery recommendation from an {@link
 * AIRecoveryProvider}. This is advisory only - {@code
 * com.recoverai.policy.RecoveryPolicyService} remains the sole
 * authorization boundary, and {@code confidence} is never itself a safety
 * signal (see {@code RecoveryAgentService}).
 * <p>
 * {@code expectedRecoveryValue} is a prediction, never a claim of actual
 * recovered money - {@code amountRecovered} on {@code RecoveryAttempt} is
 * the only field that represents real, executed recovery, and this phase
 * writes none of those.
 */
public record RecoveryRecommendation(
        UUID transactionId,
        RecoveryAction recommendedAction,
        BigDecimal confidence,
        String rationale,
        InterventionType interventionType,
        BigDecimal expectedRecoveryValue,
        Urgency urgency,
        String provider,
        String model
) {
}
