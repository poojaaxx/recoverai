package com.recoverai.policy;

import com.recoverai.config.RecoveryPolicyProperties;
import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Customer;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.PolicyCheckResponse;
import com.recoverai.dto.RecoveryPolicyDecisionResponse;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The deterministic authorization boundary between a recovery
 * recommendation and an actually-executed recovery action:
 * <pre>
 *   AI Recommendation (Phase 5) --&gt; RecoveryPolicyService --&gt; ALLOW/BLOCK/ESCALATE/STOP --&gt; Execution (Phase 7)
 * </pre>
 * This service never executes a recovery action, calls Razorpay, or calls
 * any AI/LLM provider - it only evaluates whether a proposed {@link
 * RecoveryAction} would currently be allowed, and returns a structured,
 * deterministic, auditable decision. Every input that matters (transaction
 * status, amount, recovery-attempt history, risk level) is loaded fresh
 * from the database - a caller can propose an action but can never supply
 * the facts the decision is based on.
 * <p>
 * Checks are evaluated in a fixed priority order and short-circuit at the
 * first one that determines the outcome, so {@code policyChecks} in the
 * response only ever contains checks that were actually relevant to the
 * decision:
 * <ol>
 *   <li>{@code TRANSACTION_STATUS} - already-resolved/escalated/stopped transactions are handled first and unconditionally.</li>
 *   <li>{@code CUSTOMER_CONSENT} - an opted-out customer blocks every autonomous action (Phase 14).</li>
 *   <li>{@code ACTION_COMPATIBILITY} - is this action valid for this transaction's state (and does the action itself, e.g. ESCALATE/STOP, dictate the outcome)?</li>
 *   <li>{@code RETRY_LIMIT} - only for RETRY_PAYMENT.</li>
 *   <li>{@code REPEATED_FAILURE} - total recovery actions of any kind already recorded for this transaction.</li>
 *   <li>{@code AMOUNT_LIMIT} - the financial safety boundary; high value escalates regardless of risk score.</li>
 *   <li>{@code DUPLICATE_ACTION} - was this exact action already executed/in-flight recently?</li>
 *   <li>{@code RISK_FLAGS} - Phase 3's risk level may force escalation, but never authorizes execution by itself.</li>
 * </ol>
 * If every applicable check passes, the decision is {@code ALLOW}.
 */
@Service
@RequiredArgsConstructor
public class RecoveryPolicyService {

    private static final String EVENT_TYPE = "RECOVERY_POLICY_EVALUATED";
    private static final String ACTOR = "POLICY_ENGINE";

    private final TransactionRepository transactionRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final RevenueRiskRepository revenueRiskRepository;
    private final AuditLogRepository auditLogRepository;
    private final RecoveryPolicyProperties properties;

    @Transactional
    public RecoveryPolicyDecisionResponse evaluate(UUID transactionId, RecoveryAction action) {
        // findByIdWithCustomer (not plain findById): checkConsent below reads
        // transaction.getCustomer().isRecoveryContactAllowed(), which a lazy
        // proxy can only satisfy inside an open Hibernate session - fetch-joining
        // here makes this safe regardless of the caller's transaction boundary.
        Transaction transaction = transactionRepository.findByIdWithCustomer(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        List<RecoveryAttempt> attempts = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transactionId);
        Optional<RevenueRisk> risk = revenueRiskRepository.findByTransactionId(transactionId);

        Evaluation evaluation = runChecks(transaction, action, attempts, risk);
        recordAudit(transaction, action, evaluation);

        return new RecoveryPolicyDecisionResponse(
                transaction.getId(), transaction.getExternalTransactionId(), action,
                evaluation.decision(), evaluation.requiresApproval(), evaluation.reason(),
                evaluation.checks(), Instant.now());
    }

    // ---------------------------------------------------------------- evaluation pipeline

    private record Evaluation(PolicyDecision decision, boolean requiresApproval, String reason,
                               List<PolicyCheckResponse> checks) {
    }

    private Evaluation runChecks(Transaction transaction, RecoveryAction action, List<RecoveryAttempt> attempts,
                                  Optional<RevenueRisk> risk) {
        List<PolicyCheckResponse> checks = new ArrayList<>();

        Optional<Evaluation> outcome = checkTransactionStatus(transaction, checks);
        if (outcome.isPresent()) return outcome.get();

        outcome = checkConsent(transaction, checks);
        if (outcome.isPresent()) return outcome.get();

        outcome = checkActionCompatibility(transaction, action, checks);
        if (outcome.isPresent()) return outcome.get();

        if (action == RecoveryAction.RETRY_PAYMENT) {
            outcome = checkRetryLimit(attempts, checks);
            if (outcome.isPresent()) return outcome.get();
        }

        outcome = checkRepeatedFailure(attempts, checks);
        if (outcome.isPresent()) return outcome.get();

        outcome = checkAmountLimit(transaction, checks);
        if (outcome.isPresent()) return outcome.get();

        outcome = checkDuplicateAction(attempts, action, checks);
        if (outcome.isPresent()) return outcome.get();

        outcome = checkCooldown(attempts, checks);
        if (outcome.isPresent()) return outcome.get();

        outcome = checkRiskFlags(risk, checks);
        if (outcome.isPresent()) return outcome.get();

        checks.add(new PolicyCheckResponse("HUMAN_APPROVAL", true,
                "No human approval required; all autonomous safety constraints are satisfied."));
        String reason = "%s is within all configured safety limits; the transaction is eligible for autonomous recovery."
                .formatted(actionLabel(action));
        return new Evaluation(PolicyDecision.ALLOW, false, reason, checks);
    }

    /** Already-resolved, escalated, or stopped transactions are decided here, before any action-specific logic runs. */
    private Optional<Evaluation> checkTransactionStatus(Transaction transaction, List<PolicyCheckResponse> checks) {
        TransactionStatus status = transaction.getStatus();
        if (status == TransactionStatus.SUCCESS) {
            checks.add(new PolicyCheckResponse("TRANSACTION_STATUS", false, "Transaction already completed successfully."));
            return Optional.of(new Evaluation(PolicyDecision.BLOCK, false,
                    "Transaction already completed successfully; no recovery action is applicable.", checks));
        }
        if (status == TransactionStatus.RECOVERED) {
            checks.add(new PolicyCheckResponse("TRANSACTION_STATUS", false, "Transaction has already been recovered."));
            return Optional.of(new Evaluation(PolicyDecision.BLOCK, false,
                    "Transaction has already been recovered; revenue has been collected.", checks));
        }
        if (status == TransactionStatus.ESCALATED) {
            checks.add(new PolicyCheckResponse("TRANSACTION_STATUS", false,
                    "Transaction has already been escalated for manual review."));
            return Optional.of(new Evaluation(PolicyDecision.ESCALATE, true,
                    "Transaction has already been escalated for manual review; autonomous recovery does not proceed.", checks));
        }
        if (status == TransactionStatus.STOPPED) {
            checks.add(new PolicyCheckResponse("TRANSACTION_STATUS", false, "Transaction has reached a stopping condition."));
            return Optional.of(new Evaluation(PolicyDecision.STOP, false,
                    "Transaction has reached a stopping condition; autonomous recovery remains halted.", checks));
        }
        checks.add(new PolicyCheckResponse("TRANSACTION_STATUS", true,
                "Transaction status (%s) permits recovery evaluation.".formatted(status)));
        return Optional.empty();
    }

    /**
     * Phase 14 - customer consent / do-not-contact compliance boundary.
     * Evaluated right after transaction status (so an already-resolved
     * transaction still reports that reason first) but before any
     * action-specific logic, since an opt-out blocks every autonomous
     * action equally - retry, payment link, or reminder. Decision is
     * {@code BLOCK}, not {@code STOP}: {@code STOP} now persists a durable
     * {@code STOPPED} transaction status (see {@code
     * RecoveryExecutionService.applyLifecycleStatus}), which would survive
     * the customer later opting back in; {@code BLOCK} causes no lifecycle
     * transition, so consent is simply re-checked fresh on every
     * evaluation. Server-side only - {@link Customer#isRecoveryContactAllowed()}
     * is read from the persisted entity; no endpoint accepts a
     * client-supplied override.
     */
    private Optional<Evaluation> checkConsent(Transaction transaction, List<PolicyCheckResponse> checks) {
        Customer customer = transaction.getCustomer();
        if (customer.isRecoveryContactAllowed()) {
            checks.add(new PolicyCheckResponse("CUSTOMER_CONSENT", true,
                    "Customer has not opted out of recovery contact."));
            return Optional.empty();
        }
        String reason = "Customer has opted out of recovery contact; autonomous recovery is blocked for this transaction.";
        checks.add(new PolicyCheckResponse("CUSTOMER_CONSENT", false, reason));
        return Optional.of(new Evaluation(PolicyDecision.BLOCK, false, reason, checks));
    }

    /** Validates the proposed action against the (already-known-active) transaction state, and honors explicit terminal actions. */
    private Optional<Evaluation> checkActionCompatibility(Transaction transaction, RecoveryAction action,
                                                            List<PolicyCheckResponse> checks) {
        if (action == RecoveryAction.ESCALATE) {
            checks.add(new PolicyCheckResponse("ACTION_COMPATIBILITY", true, "Escalation explicitly requested."));
            return Optional.of(new Evaluation(PolicyDecision.ESCALATE, true,
                    "Escalation explicitly requested for this transaction; routed for human review.", checks));
        }
        if (action == RecoveryAction.STOP) {
            checks.add(new PolicyCheckResponse("ACTION_COMPATIBILITY", true, "Stop explicitly requested."));
            return Optional.of(new Evaluation(PolicyDecision.STOP, false,
                    "Stop explicitly requested; autonomous recovery halted for this transaction.", checks));
        }

        TransactionStatus status = transaction.getStatus();
        boolean nothingToRetry = action == RecoveryAction.RETRY_PAYMENT
                && (status == TransactionStatus.PENDING || status == TransactionStatus.ABANDONED);
        if (nothingToRetry) {
            String reason = "No failed payment exists to retry for a transaction in %s status.".formatted(status);
            checks.add(new PolicyCheckResponse("ACTION_COMPATIBILITY", false, reason));
            return Optional.of(new Evaluation(PolicyDecision.BLOCK, false, reason, checks));
        }

        checks.add(new PolicyCheckResponse("ACTION_COMPATIBILITY", true,
                "%s is a valid action for a %s transaction.".formatted(actionLabel(action), status)));
        return Optional.empty();
    }

    /** "attempt 1 -> allowed, attempt 2 -> allowed, attempt 3 -> STOP" for maxAutomaticRetryAttempts=2, counted from persisted history. */
    private Optional<Evaluation> checkRetryLimit(List<RecoveryAttempt> attempts, List<PolicyCheckResponse> checks) {
        long priorRetries = attempts.stream().filter(a -> a.getAction() == RecoveryAction.RETRY_PAYMENT).count();
        int max = properties.getMaxAutomaticRetryAttempts();
        boolean passed = priorRetries < max;
        checks.add(new PolicyCheckResponse("RETRY_LIMIT", passed,
                "%d of %d automatic retry attempts used.".formatted(priorRetries, max)));
        if (passed) return Optional.empty();
        return Optional.of(new Evaluation(PolicyDecision.STOP, false,
                "Maximum automatic retry attempts (%d) already reached for this transaction; autonomous retries are stopped."
                        .formatted(max), checks));
    }

    /** Backstop for mixed-action exhaustion (e.g. a retry plus a reminder plus a payment link) that RETRY_LIMIT alone would not catch. */
    private Optional<Evaluation> checkRepeatedFailure(List<RecoveryAttempt> attempts, List<PolicyCheckResponse> checks) {
        int total = attempts.size();
        int max = properties.getMaxRecoveryActionsPerTransaction();
        boolean passed = total < max;
        checks.add(new PolicyCheckResponse("REPEATED_FAILURE", passed,
                "%d of %d total recovery actions used for this transaction.".formatted(total, max)));
        if (passed) return Optional.empty();
        return Optional.of(new Evaluation(PolicyDecision.STOP, false,
                "Maximum recovery actions per transaction (%d) already reached; autonomous recovery is stopped."
                        .formatted(max), checks));
    }

    /** High value does not mean high risk (Phase 3) - it independently requires human approval regardless of recovery probability. */
    private Optional<Evaluation> checkAmountLimit(Transaction transaction, List<PolicyCheckResponse> checks) {
        BigDecimal amount = transaction.getAmount();
        BigDecimal max = properties.getMaxAutonomousRecoveryAmount();
        boolean passed = amount.compareTo(max) <= 0;
        checks.add(new PolicyCheckResponse("AMOUNT_LIMIT", passed,
                "Transaction amount %s is %s the autonomous recovery limit of %s."
                        .formatted(amount, passed ? "within" : "above", max)));
        if (passed) return Optional.empty();
        return Optional.of(new Evaluation(PolicyDecision.ESCALATE, true,
                "Transaction amount of %s exceeds the autonomous recovery limit of %s; human approval is required."
                        .formatted(amount, max), checks));
    }

    /** Prevents re-running an action that already succeeded or is in flight for this transaction within the configured window. */
    private Optional<Evaluation> checkDuplicateAction(List<RecoveryAttempt> attempts, RecoveryAction action,
                                                        List<PolicyCheckResponse> checks) {
        long windowHours = properties.getDuplicateActionWindowHours();
        Instant cutoff = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        Optional<RecoveryAttempt> duplicate = attempts.stream()
                .filter(a -> a.getAction() == action)
                .filter(a -> a.getStatus() == RecoveryAttemptStatus.SUCCESS || a.getStatus() == RecoveryAttemptStatus.PENDING)
                .filter(a -> a.getExecutedAt() != null && a.getExecutedAt().isAfter(cutoff))
                .findFirst();

        boolean passed = duplicate.isEmpty();
        if (passed) {
            checks.add(new PolicyCheckResponse("DUPLICATE_ACTION", true,
                    "No duplicate %s action found within the last %d hours.".formatted(actionLabel(action), windowHours)));
            return Optional.empty();
        }

        String statusWord = duplicate.get().getStatus() == RecoveryAttemptStatus.SUCCESS ? "succeeded" : "is pending";
        String reason = "A %s action already %s for this transaction within the last %d hours; duplicate action prevented."
                .formatted(actionLabel(action), statusWord, windowHours);
        checks.add(new PolicyCheckResponse("DUPLICATE_ACTION", false, reason));
        return Optional.of(new Evaluation(PolicyDecision.BLOCK, false, reason, checks));
    }

    /**
     * P1.2 - a general pacing rule, distinct from {@link #checkDuplicateAction}:
     * that check only blocks repeating the exact same action; this blocks
     * <i>any</i> autonomous action within {@code minCooldownMinutesBetweenActions}
     * of the most recent one of any type. Decision is {@code BLOCK}, not
     * {@code STOP} - {@code STOP} now persists a durable {@code STOPPED}
     * transaction status (see {@code RecoveryExecutionService.applyLifecycleStatus}),
     * which would make a cooldown permanent instead of temporary; {@code
     * BLOCK} causes no lifecycle transition, so the transaction is simply
     * re-evaluated fresh (and likely allowed) once the cooldown elapses.
     * Disabled by default ({@code 0}) - see the property's own javadoc.
     */
    private Optional<Evaluation> checkCooldown(List<RecoveryAttempt> attempts, List<PolicyCheckResponse> checks) {
        long cooldownMinutes = properties.getMinCooldownMinutesBetweenActions();
        if (cooldownMinutes <= 0) {
            checks.add(new PolicyCheckResponse("COOLDOWN", true, "No cooldown is configured between recovery actions."));
            return Optional.empty();
        }
        Optional<Instant> mostRecent = attempts.stream().map(RecoveryAttempt::getExecutedAt)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo);
        if (mostRecent.isEmpty()) {
            checks.add(new PolicyCheckResponse("COOLDOWN", true, "No prior recovery action recorded; cooldown does not apply."));
            return Optional.empty();
        }
        Instant readyAt = mostRecent.get().plus(cooldownMinutes, ChronoUnit.MINUTES);
        boolean passed = !Instant.now().isBefore(readyAt);
        if (passed) {
            checks.add(new PolicyCheckResponse("COOLDOWN", true,
                    "The %d minute cooldown since the last recovery action has elapsed.".formatted(cooldownMinutes)));
            return Optional.empty();
        }
        String reason = "A %d minute cooldown applies since the last recovery action for this transaction; "
                + "autonomous recovery is temporarily paused, not stopped.".formatted(cooldownMinutes);
        checks.add(new PolicyCheckResponse("COOLDOWN", false, reason));
        return Optional.of(new Evaluation(PolicyDecision.BLOCK, false, reason, checks));
    }

    /** Risk level can force escalation but - per design - never by itself authorizes an ALLOW. */
    private Optional<Evaluation> checkRiskFlags(Optional<RevenueRisk> risk, List<PolicyCheckResponse> checks) {
        RiskLevel level = risk.map(RevenueRisk::getRiskLevel).orElse(null);
        if (level == RiskLevel.CRITICAL) {
            checks.add(new PolicyCheckResponse("RISK_FLAGS", false, "Transaction is classified CRITICAL risk."));
            return Optional.of(new Evaluation(PolicyDecision.ESCALATE, true,
                    "Transaction is classified CRITICAL risk; human review is required before autonomous recovery.", checks));
        }
        String reason = level == null
                ? "No risk assessment available for this transaction."
                : "Risk level (%s) does not require additional escalation.".formatted(level);
        checks.add(new PolicyCheckResponse("RISK_FLAGS", true, reason));
        return Optional.empty();
    }

    // ---------------------------------------------------------------- audit trail

    /**
     * Writes one {@code RECOVERY_POLICY_EVALUATED} audit row per meaningful
     * state transition - not per call. An evaluation endpoint is expected
     * to be polled/repeated (e.g. by a future dashboard) without side
     * effects, so a repeated evaluation that reaches the same decision for
     * the same proposed action is not re-logged, to keep the audit trail
     * demo-readable instead of noisy.
     */
    private void recordAudit(Transaction transaction, RecoveryAction action, Evaluation evaluation) {
        Optional<AuditLog> last = auditLogRepository
                .findTopByTransactionIdAndEventTypeOrderByTimestampDesc(transaction.getId(), EVENT_TYPE);
        boolean unchanged = last.isPresent()
                && evaluation.decision().name().equals(last.get().getDecision())
                && last.get().getMetadata() != null
                && action.name().equals(last.get().getMetadata().get("action"));
        if (unchanged) {
            return;
        }

        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction)
                .eventType(EVENT_TYPE)
                .actor(ACTOR)
                .decision(evaluation.decision().name())
                .reason(evaluation.reason())
                .metadata(Map.of(
                        "action", action.name(),
                        "requiresHumanApproval", evaluation.requiresApproval()
                ))
                .timestamp(Instant.now())
                .build());
    }

    private static String actionLabel(RecoveryAction action) {
        return switch (action) {
            case RETRY_PAYMENT -> "Retry";
            case CREATE_PAYMENT_LINK -> "Payment link creation";
            case SEND_RECOVERY_REMINDER -> "Recovery reminder";
            case ESCALATE -> "Escalation";
            case STOP -> "Stop";
        };
    }
}
