package com.recoverai.agent;

import com.recoverai.domain.InterventionType;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.domain.Urgency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic, offline, no-API-key recovery reasoning - the default
 * {@link AIRecoveryProvider} and the one every automated test runs
 * against. It is not a lookup table keyed on failure category; it weighs
 * transaction status, recovery probability (from Phase 3's risk analysis
 * when available, otherwise a Bayesian-smoothed estimate from customer
 * history), prior attempt count, amount, and risk level - the same kind
 * of evidence a real model would be given - to select an action,
 * confidence, and urgency. The same context always produces the same
 * recommendation.
 * <p>
 * Its attempt-count "give up" threshold ({@link #STOP_ATTEMPT_THRESHOLD})
 * is deliberately its own heuristic, distinct from {@code
 * RecoveryPolicyProperties.maxAutomaticRetryAttempts} - the AI does not
 * know or enforce Phase 4's exact policy thresholds, which is what makes
 * "AI recommends RETRY_PAYMENT, policy overrides to STOP/ESCALATE" a real,
 * naturally-occurring outcome rather than a contrived one - see {@code
 * RecoveryAgentServiceTest}'s policy-override tests.
 */
public class MockAIRecoveryProvider implements AIRecoveryProvider {

    static final String PROVIDER_NAME = "mock";
    static final String MODEL_NAME = "recoverai-mock-v1";

    private static final BigDecimal RETRY_PROBABILITY_THRESHOLD = new BigDecimal("0.50");
    private static final BigDecimal LINK_PROBABILITY_THRESHOLD = new BigDecimal("0.30");
    private static final BigDecimal RETRY_ATTEMPT_THRESHOLD = new BigDecimal("2");
    private static final int STOP_ATTEMPT_THRESHOLD = 4;
    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("30000");
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.05");
    private static final BigDecimal MAX_CONFIDENCE = new BigDecimal("0.95");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    @Override
    public RecoveryRecommendation recommend(RecoveryAgentContext context) {
        RecoveryAgentContext.TransactionContext txn = context.transaction();
        TransactionStatus status = txn.status();

        if (status == TransactionStatus.SUCCESS || status == TransactionStatus.RECOVERED) {
            return recommendation(context, RecoveryAction.STOP, new BigDecimal("0.99"),
                    "Transaction is already resolved (%s); no recovery action is needed.".formatted(status),
                    InterventionType.STOP, ZERO, Urgency.LOW);
        }
        if (status == TransactionStatus.STOPPED) {
            return recommendation(context, RecoveryAction.STOP, new BigDecimal("0.95"),
                    "Transaction has already reached a stopping condition; recommending no further automated action.",
                    InterventionType.STOP, ZERO, Urgency.LOW);
        }

        BigDecimal recoveryProbability = recoveryProbability(context);
        BigDecimal expected = expectedRecoveryValue(context, recoveryProbability);
        int totalAttempts = context.recoveryHistory().totalAttempts();

        if (status == TransactionStatus.ESCALATED) {
            return recommendation(context, RecoveryAction.ESCALATE, new BigDecimal("0.90"),
                    "Transaction is already escalated and awaiting manual review; escalation remains the appropriate path.",
                    InterventionType.ESCALATE, expected, Urgency.MEDIUM);
        }
        if (context.risk() != null && context.risk().riskLevel() == RiskLevel.CRITICAL) {
            return recommendation(context, RecoveryAction.ESCALATE, new BigDecimal("0.85"),
                    "Transaction is classified critical risk; recommending escalation for human review.",
                    InterventionType.ESCALATE, expected, Urgency.HIGH);
        }
        if (totalAttempts >= STOP_ATTEMPT_THRESHOLD) {
            return recommendation(context, RecoveryAction.STOP, new BigDecimal("0.70"),
                    "Multiple recovery attempts (%d) have already been made without success; recommending the system stop retrying."
                            .formatted(totalAttempts),
                    InterventionType.STOP, ZERO, Urgency.LOW);
        }
        if (status == TransactionStatus.PENDING) {
            return recommendation(context, RecoveryAction.SEND_RECOVERY_REMINDER, clampConfidence(recoveryProbability),
                    "Payment is pending confirmation; a reminder may help the customer complete it.",
                    InterventionType.REENGAGE, expected, Urgency.MEDIUM);
        }
        if (status == TransactionStatus.ABANDONED) {
            return recommendation(context, RecoveryAction.CREATE_PAYMENT_LINK, clampConfidence(recoveryProbability),
                    "Checkout was abandoned; a fresh payment link supports re-engagement better than retrying a payment that never completed.",
                    InterventionType.REENGAGE, expected, Urgency.MEDIUM);
        }

        // status == FAILED
        boolean highValue = txn.amount().compareTo(HIGH_VALUE_THRESHOLD) >= 0;
        if (recoveryProbability.compareTo(RETRY_PROBABILITY_THRESHOLD) >= 0
                && BigDecimal.valueOf(totalAttempts).compareTo(RETRY_ATTEMPT_THRESHOLD) < 0) {
            return recommendation(context, RecoveryAction.RETRY_PAYMENT, clampConfidence(recoveryProbability),
                    "Failure (%s) has a reasonable recovery probability and limited retry history; recommending an automatic retry."
                            .formatted(txn.failureCategory()),
                    InterventionType.RETRY, expected, highValue ? Urgency.HIGH : Urgency.MEDIUM);
        }
        if (recoveryProbability.compareTo(LINK_PROBABILITY_THRESHOLD) >= 0) {
            return recommendation(context, RecoveryAction.CREATE_PAYMENT_LINK, clampConfidence(recoveryProbability),
                    "Recovery probability is moderate and prior attempts have not succeeded; offering a fresh payment link instead of repeating the same retry.",
                    InterventionType.REENGAGE, expected, Urgency.MEDIUM);
        }
        return recommendation(context, RecoveryAction.ESCALATE, clampConfidence(BigDecimal.ONE.subtract(recoveryProbability)),
                "Recovery probability is low and prior automatic attempts have not helped; recommending escalation rather than further automated attempts.",
                InterventionType.ESCALATE, expected, Urgency.MEDIUM);
    }

    /** Phase 3's recovery probability when available; otherwise the same Bayesian-smoothed neutral estimate Phase 3 uses, computed independently here so this provider has no compile-time dependency on the risk engine's internals. */
    private static BigDecimal recoveryProbability(RecoveryAgentContext context) {
        if (context.risk() != null) {
            return context.risk().recoveryProbability();
        }
        BigDecimal success = BigDecimal.valueOf(context.customer().successfulPaymentCount());
        BigDecimal failed = BigDecimal.valueOf(context.customer().failedPaymentCount());
        return success.add(BigDecimal.ONE)
                .divide(success.add(failed).add(BigDecimal.valueOf(2)), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal expectedRecoveryValue(RecoveryAgentContext context, BigDecimal recoveryProbability) {
        if (context.risk() != null) {
            return context.risk().potentialRecoveryValue();
        }
        return context.transaction().amount().multiply(recoveryProbability).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal clampConfidence(BigDecimal value) {
        BigDecimal scaled = value.setScale(4, RoundingMode.HALF_UP);
        if (scaled.compareTo(MIN_CONFIDENCE) < 0) return MIN_CONFIDENCE;
        if (scaled.compareTo(MAX_CONFIDENCE) > 0) return MAX_CONFIDENCE;
        return scaled;
    }

    private static RecoveryRecommendation recommendation(RecoveryAgentContext context, RecoveryAction action,
                                                           BigDecimal confidence, String rationale,
                                                           InterventionType interventionType,
                                                           BigDecimal expectedRecoveryValue, Urgency urgency) {
        return new RecoveryRecommendation(context.transaction().transactionId(), action, confidence, rationale,
                interventionType, expectedRecoveryValue, urgency, PROVIDER_NAME, MODEL_NAME);
    }
}
