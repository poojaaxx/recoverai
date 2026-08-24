package com.recoverai.dto;

import com.recoverai.agent.RecoveryRecommendation;
import com.recoverai.domain.InterventionType;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.Urgency;

import java.math.BigDecimal;

/**
 * Read-only projection of a {@link RecoveryRecommendation}. {@code
 * confidence} is the AI's own stated confidence - distinct from Phase 3's
 * {@code riskScore}/{@code recoveryProbability} and never itself a safety
 * signal. {@code expectedRecoveryValue} is a prediction, never a claim of
 * actual recovered money.
 */
public record AIRecommendationResponse(
        RecoveryAction action,
        BigDecimal confidence,
        String rationale,
        InterventionType interventionType,
        BigDecimal expectedRecoveryValue,
        Urgency urgency,
        String provider,
        String model,
        boolean providerAvailable
) {
    public static AIRecommendationResponse from(RecoveryRecommendation r, boolean providerAvailable) {
        return new AIRecommendationResponse(r.recommendedAction(), r.confidence(), r.rationale(), r.interventionType(),
                r.expectedRecoveryValue(), r.urgency(), r.provider(), r.model(), providerAvailable);
    }
}
