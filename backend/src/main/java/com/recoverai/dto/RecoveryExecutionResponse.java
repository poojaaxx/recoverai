package com.recoverai.dto;

import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.payment.PaymentFailureReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of the full Phase 7 pipeline: AI recommendation -&gt; policy
 * authorization -&gt; (only if {@code ALLOW}) execution via {@code
 * PaymentGateway} -&gt; persisted {@code RecoveryAttempt}.
 * <p>
 * {@code executed=true} only when a real (mock or Razorpay) provider call
 * actually happened for this request. {@code executionStatus} mirrors the
 * persisted {@link RecoveryAttemptStatus} ({@code SUCCESS}/{@code FAILED})
 * and is {@code null} whenever nothing was executed (policy did not
 * {@code ALLOW}, or the authorized action was not a payment-gateway
 * action). {@code amountRecovered} is a confirmed-recovery figure only -
 * see {@code RecoveryExecutionService}'s javadoc for why it is {@code 0}
 * for every result this phase can currently produce.
 * <p>
 * {@code recommendation}/{@code policyDecision} are {@code null} only in
 * the rare case this response resolves a genuine concurrent duplicate
 * request (see {@code duplicate}) - the facts about the actual execution
 * (```executed```, {@code recoveryAttemptId}, {@code provider}, {@code
 * amountRecovered}, ...) are still accurate, drawn from the winning
 * attempt, which already reported its own recommendation/policyDecision
 * to whichever request performed it.
 */
public record RecoveryExecutionResponse(
        UUID transactionId,
        String externalTransactionId,
        AIRecommendationResponse recommendation,
        RecoveryPolicyDecisionResponse policyDecision,
        boolean requiresHumanApproval,
        boolean executed,
        UUID recoveryAttemptId,
        RecoveryAction action,
        String provider,
        String providerReference,
        RecoveryAttemptStatus executionStatus,
        BigDecimal amount,
        BigDecimal amountRecovered,
        boolean simulated,
        PaymentFailureReason failureCode,
        String failureReason,
        boolean duplicate,
        String executionNote,
        UUID auditEventId,
        Instant executedAt
) {
}
