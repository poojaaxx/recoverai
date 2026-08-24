package com.recoverai.agent;

import com.recoverai.config.RecoveryPolicyProperties;
import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.AIRecommendationResponse;
import com.recoverai.dto.RecoveryAgentBatchResponse;
import com.recoverai.dto.RecoveryAgentEvaluationResponse;
import com.recoverai.dto.RecoveryPolicyDecisionResponse;
import com.recoverai.policy.RecoveryPolicyService;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.recoverai.domain.InterventionType;
import com.recoverai.domain.Urgency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the Phase 5 pipeline:
 * <pre>
 *   Transaction -&gt; RecoveryAgentContext -&gt; AIRecoveryProvider -&gt; RecoveryRecommendation
 *              -&gt; RecoveryPolicyService.evaluate(...) -&gt; PolicyDecision -&gt; finalAction
 * </pre>
 * This service never executes a {@link RecoveryAction} itself and never
 * calls Razorpay - it builds context, asks the AI, and hands the AI's
 * recommended action to {@link RecoveryPolicyService} exactly as Phase 4
 * already expects to receive one from any caller. The policy decision is
 * always authoritative: {@code finalAction} reflects {@link
 * PolicyDecision}, never the raw AI recommendation, whenever they differ -
 * see {@code resolveFinalAction}.
 */
@Service
@RequiredArgsConstructor
public class RecoveryAgentService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryAgentService.class);

    private static final String EVENT_TYPE = "RECOVERY_AI_RECOMMENDATION";
    private static final String ACTOR = "AI_AGENT";

    /** Same revenue-loss-state target set Phase 3's batch analysis uses. */
    private static final Set<TransactionStatus> AT_RISK_STATUSES = EnumSet.of(
            TransactionStatus.FAILED, TransactionStatus.PENDING, TransactionStatus.ABANDONED,
            TransactionStatus.ESCALATED, TransactionStatus.STOPPED);

    private final TransactionRepository transactionRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final RevenueRiskRepository revenueRiskRepository;
    private final AuditLogRepository auditLogRepository;
    private final RecoveryPolicyService recoveryPolicyService;
    private final RecoveryPolicyProperties policyProperties;
    private final AIRecoveryProvider aiRecoveryProvider;

    @Transactional
    public RecoveryAgentEvaluationResponse evaluate(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        EvaluationResult result = evaluateTransaction(transaction, true);
        return toResponse(result);
    }

    /**
     * Batch AI recommendations over every currently at-risk transaction,
     * with aggregated statistics only - no execution, no per-transaction
     * audit rows (see {@link #evaluateTransaction} javadoc for why).
     * Reuses the single-transaction pipeline per row rather than
     * duplicating Phase 3/4's fetch-join optimizations, so this is O(n)
     * queries rather than O(1)-amortized like {@code
     * RevenueRiskService.analyzeAllAtRisk()} - an intentional simplicity
     * tradeoff for a recommendation-only batch capability; see
     * docs/ARCHITECTURE.md.
     */
    @Transactional
    public RecoveryAgentBatchResponse evaluateAll() {
        List<Transaction> eligible = transactionRepository.findByStatusInWithCustomer(AT_RISK_STATUSES);

        Map<RecoveryAction, Long> byAction = new EnumMap<>(RecoveryAction.class);
        Map<PolicyDecision, Long> byDecision = new EnumMap<>(PolicyDecision.class);
        BigDecimal confidenceSum = BigDecimal.ZERO;
        long providerFailures = 0;
        long malformedOutputs = 0;

        for (Transaction transaction : eligible) {
            EvaluationResult result = evaluateTransaction(transaction, false);
            byAction.merge(result.outcome().recommendation().recommendedAction(), 1L, Long::sum);
            byDecision.merge(result.policyDecision().decision(), 1L, Long::sum);
            confidenceSum = confidenceSum.add(result.outcome().recommendation().confidence());
            if (result.outcome().providerFailed()) providerFailures++;
            if (result.outcome().malformed()) malformedOutputs++;
        }

        BigDecimal averageConfidence = eligible.isEmpty()
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : confidenceSum.divide(BigDecimal.valueOf(eligible.size()), 4, RoundingMode.HALF_UP);

        return new RecoveryAgentBatchResponse(eligible.size(), byAction, byDecision, averageConfidence,
                providerFailures, malformedOutputs);
    }

    // ---------------------------------------------------------------- pipeline

    private record EvaluationResult(Transaction transaction, ProviderOutcome outcome,
                                     RecoveryPolicyDecisionResponse policyDecision, RecoveryAction finalAction,
                                     UUID auditEventId) {
    }

    /**
     * {@code writeAudit=false} is used only by {@link #evaluateAll()} - a
     * 500-transaction batch writing one {@code RECOVERY_AI_RECOMMENDATION}
     * row per row would flood the audit trail for a call whose whole
     * purpose is aggregate statistics, not a per-transaction decision
     * record (mirroring the audit-noise reasoning already applied to
     * {@code RecoveryPolicyService}'s own audit dedup). {@code
     * RecoveryPolicyService.evaluate} - called from here regardless -
     * still writes/dedupes its own {@code RECOVERY_POLICY_EVALUATED} row
     * exactly as it does for any other caller.
     */
    private EvaluationResult evaluateTransaction(Transaction transaction, boolean writeAudit) {
        RecoveryAgentContext context = buildContext(transaction);
        ProviderOutcome outcome = obtainRecommendation(transaction.getId(), context);

        RecoveryPolicyDecisionResponse policyDecision = recoveryPolicyService.evaluate(
                transaction.getId(), outcome.recommendation().recommendedAction());

        RecoveryAction finalAction = resolveFinalAction(policyDecision, outcome.recommendation());
        UUID auditEventId = writeAudit ? recordAudit(transaction, outcome.recommendation(), policyDecision) : null;

        return new EvaluationResult(transaction, outcome, policyDecision, finalAction, auditEventId);
    }

    private RecoveryAgentEvaluationResponse toResponse(EvaluationResult result) {
        return new RecoveryAgentEvaluationResponse(
                result.transaction().getId(),
                result.transaction().getExternalTransactionId(),
                AIRecommendationResponse.from(result.outcome().recommendation(), !result.outcome().providerFailed()),
                result.policyDecision(),
                result.finalAction(),
                result.policyDecision().requiresHumanApproval(),
                result.outcome().recommendation().expectedRecoveryValue(),
                result.auditEventId(),
                Instant.now());
    }

    /** ALLOW keeps the AI's own choice; ESCALATE/STOP always resolve to that exact action regardless of what the AI recommended; BLOCK means nothing is applicable. */
    private static RecoveryAction resolveFinalAction(RecoveryPolicyDecisionResponse policyDecision,
                                                       RecoveryRecommendation recommendation) {
        return switch (policyDecision.decision()) {
            case ALLOW -> recommendation.recommendedAction();
            case ESCALATE -> RecoveryAction.ESCALATE;
            case STOP -> RecoveryAction.STOP;
            case BLOCK -> null;
        };
    }

    // ---------------------------------------------------------------- context building (authoritative, database-only)

    private RecoveryAgentContext buildContext(Transaction transaction) {
        Customer customer = transaction.getCustomer();
        List<RecoveryAttempt> attempts = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transaction.getId());
        Optional<RevenueRisk> risk = revenueRiskRepository.findByTransactionId(transaction.getId());

        var transactionContext = new RecoveryAgentContext.TransactionContext(
                transaction.getId(), transaction.getExternalTransactionId(), transaction.getAmount(),
                transaction.getCurrency(), transaction.getStatus(), transaction.getPaymentMethod(),
                resolveFailureCategory(transaction), transaction.getAttemptCount(), transaction.getCreatedAt());

        var customerContext = new RecoveryAgentContext.CustomerContext(
                customer.getId(), customer.getSuccessfulPaymentCount(), customer.getFailedPaymentCount(),
                customer.getTotalHistoricalValue());

        RecoveryAgentContext.RiskContext riskContext = risk.map(r -> new RecoveryAgentContext.RiskContext(
                r.getRiskScore(), r.getRiskLevel(), r.getAmountAtRisk(), r.getRecoveryProbability(),
                r.getAmountAtRisk().multiply(r.getRecoveryProbability()).setScale(2, RoundingMode.HALF_UP),
                r.getFactors(), r.getReason())).orElse(null);

        long failedAttempts = attempts.stream().filter(a -> a.getStatus() == RecoveryAttemptStatus.FAILED).count();
        long successfulAttempts = attempts.stream().filter(a -> a.getStatus() == RecoveryAttemptStatus.SUCCESS).count();
        RecoveryAttempt last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);

        var historyContext = new RecoveryAgentContext.RecoveryHistoryContext(
                attempts.size(), (int) failedAttempts, (int) successfulAttempts,
                last == null ? null : last.getExecutedAt(), last == null ? null : last.getAction());

        var policyContext = new RecoveryAgentContext.PolicyContext(
                policyProperties.getMaxAutomaticRetryAttempts(), policyProperties.getMaxAutonomousRecoveryAmount(),
                policyProperties.getMaxRecoveryActionsPerTransaction(), policyProperties.getDuplicateActionWindowHours());

        return new RecoveryAgentContext(transactionContext, customerContext, riskContext, historyContext, policyContext);
    }

    private static FailureCategory resolveFailureCategory(Transaction transaction) {
        String code = transaction.getFailureCode();
        if (code == null) {
            return FailureCategory.UNKNOWN;
        }
        try {
            return FailureCategory.valueOf(code);
        } catch (IllegalArgumentException e) {
            return FailureCategory.UNKNOWN;
        }
    }

    // ---------------------------------------------------------------- provider call, validation, fail-closed fallback

    private record ProviderOutcome(RecoveryRecommendation recommendation, boolean providerFailed, boolean malformed) {
    }

    /**
     * Any provider exception (network error, timeout, malformed
     * exception-throwing bug) or any recommendation that fails {@link
     * #isValid} is replaced with a safe fallback - deliberately broad
     * {@code catch (Exception e)} here, because this is the fail-closed
     * boundary: a provider bug must degrade to "AI unavailable, escalate
     * for manual review," never propagate into a broken endpoint or an
     * unvalidated recommendation reaching the policy engine.
     */
    private ProviderOutcome obtainRecommendation(UUID transactionId, RecoveryAgentContext context) {
        RecoveryRecommendation recommendation;
        try {
            recommendation = aiRecoveryProvider.recommend(context);
        } catch (Exception e) {
            log.warn("AI provider failed for transaction {}: {}", transactionId, e.toString());
            return new ProviderOutcome(
                    safeFallback(transactionId, "AI provider unavailable; escalating for manual review as a safe default."),
                    true, false);
        }

        if (!isValid(transactionId, recommendation)) {
            log.warn("AI provider returned invalid output for transaction {}: {}", transactionId, recommendation);
            return new ProviderOutcome(
                    safeFallback(transactionId, "AI output failed validation; escalating for manual review as a safe default."),
                    false, true);
        }
        return new ProviderOutcome(recommendation, false, false);
    }

    /** Never trust raw AI output - reject unknown actions, out-of-range confidence, negative expected value, mismatched/missing fields. */
    private static boolean isValid(UUID transactionId, RecoveryRecommendation r) {
        if (r == null) return false;
        if (!transactionId.equals(r.transactionId())) return false;
        if (r.recommendedAction() == null) return false;
        if (r.interventionType() == null) return false;
        if (r.urgency() == null) return false;
        if (r.rationale() == null || r.rationale().isBlank()) return false;
        if (r.confidence() == null
                || r.confidence().compareTo(BigDecimal.ZERO) < 0
                || r.confidence().compareTo(BigDecimal.ONE) > 0) {
            return false;
        }
        if (r.expectedRecoveryValue() == null || r.expectedRecoveryValue().compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }
        return true;
    }

    private static RecoveryRecommendation safeFallback(UUID transactionId, String rationale) {
        return new RecoveryRecommendation(transactionId, RecoveryAction.ESCALATE, BigDecimal.ZERO, rationale,
                InterventionType.ESCALATE, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                Urgency.MEDIUM, "fallback", null);
    }

    // ---------------------------------------------------------------- audit trail

    private UUID recordAudit(Transaction transaction, RecoveryRecommendation recommendation,
                              RecoveryPolicyDecisionResponse policyDecision) {
        AuditLog saved = auditLogRepository.save(AuditLog.builder()
                .transaction(transaction)
                .eventType(EVENT_TYPE)
                .actor(ACTOR)
                .decision(recommendation.recommendedAction().name())
                .reason(recommendation.rationale())
                .metadata(Map.of(
                        "provider", recommendation.provider(),
                        "model", recommendation.model() == null ? "" : recommendation.model(),
                        "action", recommendation.recommendedAction().name(),
                        "confidence", recommendation.confidence(),
                        "interventionType", recommendation.interventionType().name(),
                        "expectedRecoveryValue", recommendation.expectedRecoveryValue(),
                        "policyDecision", policyDecision.decision().name()
                ))
                .timestamp(Instant.now())
                .build());
        return saved.getId();
    }
}
