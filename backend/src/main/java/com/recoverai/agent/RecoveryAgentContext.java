package com.recoverai.agent;

import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The structured, authoritative context handed to an {@link
 * AIRecoveryProvider} - built entirely from the database by {@code
 * RecoveryAgentService}, never from client input. This is the minimum
 * information necessary for recovery reasoning; it deliberately excludes
 * anything not relevant to that decision (no raw customer PII beyond an
 * id, no secrets, no unrelated transaction fields).
 * <p>
 * {@code risk} is {@code null} when the transaction has not yet been
 * analyzed by the Phase 3 risk engine - providers must handle this
 * gracefully rather than assuming it is always present.
 */
public record RecoveryAgentContext(
        TransactionContext transaction,
        CustomerContext customer,
        RiskContext risk,
        RecoveryHistoryContext recoveryHistory,
        PolicyContext policy
) {

    public record TransactionContext(
            UUID transactionId,
            String externalTransactionId,
            BigDecimal amount,
            String currency,
            TransactionStatus status,
            PaymentMethod paymentMethod,
            FailureCategory failureCategory,
            int attemptCount,
            Instant createdAt
    ) {
    }

    public record CustomerContext(
            UUID customerId,
            int successfulPaymentCount,
            int failedPaymentCount,
            BigDecimal totalHistoricalValue
    ) {
    }

    /** Absent (null) when the transaction has no {@code RevenueRisk} row yet - not an error condition. */
    public record RiskContext(
            BigDecimal riskScore,
            RiskLevel riskLevel,
            BigDecimal amountAtRisk,
            BigDecimal recoveryProbability,
            BigDecimal potentialRecoveryValue,
            List<String> factors,
            String reason
    ) {
    }

    public record RecoveryHistoryContext(
            int totalAttempts,
            int failedAttempts,
            int successfulAttempts,
            Instant lastAttemptAt,
            RecoveryAction lastAttemptAction
    ) {
    }

    /**
     * The Phase 4 policy thresholds, echoed here for the AI's situational
     * awareness only - the AI does not enforce these, and recommending
     * something that would exceed them is not an error; {@code
     * RecoveryPolicyService} is what actually applies them.
     */
    public record PolicyContext(
            int maxAutomaticRetryAttempts,
            BigDecimal maxAutonomousRecoveryAmount,
            int maxRecoveryActionsPerTransaction,
            long duplicateActionWindowHours
    ) {
    }
}
