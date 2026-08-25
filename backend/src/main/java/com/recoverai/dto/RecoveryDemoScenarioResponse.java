package com.recoverai.dto;

import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RiskLevel;
import com.recoverai.payment.PaymentFailureReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One Phase 8 demo scenario result. This is a pure read/aggregation
 * projection over real responses from {@code RevenueRiskService} and
 * {@code RecoveryExecutionService} (which itself calls {@code
 * RecoveryAgentService} and {@code RecoveryPolicyService}), plus the real
 * persisted audit trail for the transaction — no risk, AI, policy, or
 * payment decision is made here. See {@code RecoveryDemoService}.
 */
public record RecoveryDemoScenarioResponse(
        String scenarioLabel,
        UUID transactionId,
        String externalTransactionId,
        String transactionStatus,
        BigDecimal amount,
        String currency,

        BigDecimal riskScore,
        RiskLevel riskLevel,
        BigDecimal amountAtRisk,
        BigDecimal recoveryProbability,
        BigDecimal potentialRecoveryValue,
        List<String> riskFactors,
        String riskReason,

        RecoveryAction aiRecommendedAction,
        BigDecimal aiConfidence,
        String aiRationale,

        PolicyDecision policyDecision,
        String policyReason,
        boolean requiresHumanApproval,

        RecoveryAction finalAction,
        boolean executed,
        RecoveryAttemptStatus executionStatus,
        String provider,
        boolean simulated,
        BigDecimal amountRecovered,
        PaymentFailureReason failureCode,
        boolean duplicate,

        PaymentConfirmationStatus paymentConfirmationStatus,
        BigDecimal confirmedAmount,
        String providerPaymentId,
        Instant confirmedAt,

        String safetyExplanation,
        List<AuditTimelineEntryResponse> auditTimeline
) {
}
