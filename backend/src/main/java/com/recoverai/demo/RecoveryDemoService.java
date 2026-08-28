package com.recoverai.demo;

import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.Transaction;
import com.recoverai.dto.AuditTimelineEntryResponse;
import com.recoverai.dto.RecoveryDemoScenarioResponse;
import com.recoverai.dto.RecoveryDemoSummaryResponse;
import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.dto.RevenueRiskResponse;
import com.recoverai.execution.RecoveryExecutionService;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.RevenueRiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 8 — a deterministic, presentation-oriented demo over the five fixed
 * named seed transactions (created by {@code DemoDataSeeder}). This class
 * contains no risk/AI/policy/payment decision logic of its own: it only
 * calls the real production pipeline —
 * <pre>
 *   RevenueRiskService.analyzeTransaction(...)  -&gt; real Phase 3 risk scoring
 *   RecoveryExecutionService.execute(...)       -&gt; real Phase 5 AI recommendation,
 *                                                   real Phase 4 policy decision,
 *                                                   real Phase 6 PaymentGateway (only if ALLOW)
 * </pre>
 * and shapes the real responses plus the real persisted {@code AuditLog}
 * trail for display. It never calls {@code PaymentGateway} directly, never
 * calls {@code RecoveryPolicyService} directly, and never writes an
 * {@code AuditLog} row itself — every fact shown traces back to a real
 * response or a real persisted row.
 * <p>
 * <b>Repeatability.</b> Running a scenario more than once does not need a
 * special reset mechanism: {@code analyzeTransaction} is an idempotent
 * upsert (Phase 3), and a repeated {@code execute()} call is naturally
 * re-blocked by Phase 4's existing {@code DUPLICATE_ACTION} policy check
 * (or, for the already-resolved/escalated scenarios, by the same policy
 * decision every time) — so no scenario can accumulate uncontrolled {@code
 * RecoveryAttempt} rows or contradictory transaction state merely from
 * being re-run.
 */
@Service
@RequiredArgsConstructor
public class RecoveryDemoService {

    /** The five fixed demo scenarios (Phase 8 spec section 2), in presentation order. */
    private static final Map<String, String> SCENARIOS = buildScenarios();

    private static Map<String, String> buildScenarios() {
        Map<String, String> scenarios = new LinkedHashMap<>();
        scenarios.put("demo-easy-recovery", "EASY_RECOVERY");
        scenarios.put("demo-high-value", "HIGH_VALUE_ESCALATION");
        scenarios.put("demo-repeated-failure", "REPEATED_FAILURE_STOP");
        scenarios.put("demo-successful-recovery", "ALREADY_RECOVERED");
        scenarios.put("demo-retry-escalation", "ALREADY_ESCALATED");
        return scenarios;
    }

    private final TransactionRepository transactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final RevenueRiskService revenueRiskService;
    private final RecoveryExecutionService recoveryExecutionService;

    @Transactional
    public RecoveryDemoSummaryResponse runAll() {
        List<RecoveryDemoScenarioResponse> scenarios = SCENARIOS.keySet().stream()
                .map(this::runScenario)
                .toList();
        return summarize(scenarios);
    }

    @Transactional
    public RecoveryDemoScenarioResponse runOne(String externalTransactionId) {
        if (!SCENARIOS.containsKey(externalTransactionId)) {
            throw new DemoScenarioNotFoundException(externalTransactionId);
        }
        return runScenario(externalTransactionId);
    }

    // ---------------------------------------------------------------- one scenario

    private RecoveryDemoScenarioResponse runScenario(String externalTransactionId) {
        Transaction transaction = transactionRepository.findByExternalTransactionId(externalTransactionId)
                .orElseThrow(() -> new DemoScenarioNotFoundException(externalTransactionId));

        RevenueRiskResponse risk = revenueRiskService.analyzeTransaction(transaction.getId());
        RecoveryExecutionResponse execution = recoveryExecutionService.execute(transaction.getId());

        List<AuditTimelineEntryResponse> timeline = auditLogRepository
                .findByTransactionIdOrderByTimestampAsc(transaction.getId()).stream()
                .map(AuditTimelineEntryResponse::from)
                .toList();

        return toScenarioResponse(SCENARIOS.get(externalTransactionId), transaction, risk, execution, timeline);
    }

    private RecoveryDemoScenarioResponse toScenarioResponse(String label, Transaction transaction,
                                                              RevenueRiskResponse risk, RecoveryExecutionResponse execution,
                                                              List<AuditTimelineEntryResponse> timeline) {
        var aiRecommendation = execution.recommendation();
        var policyDecision = execution.policyDecision();

        return new RecoveryDemoScenarioResponse(
                label, transaction.getId(), transaction.getExternalTransactionId(),
                transaction.getStatus().name(), transaction.getAmount(), transaction.getCurrency(),

                risk.riskScore(), risk.riskLevel(), risk.amountAtRisk(), risk.recoveryProbability(),
                risk.potentialRecoveryValue(), risk.factors(), risk.reason(),

                aiRecommendation == null ? null : aiRecommendation.action(),
                aiRecommendation == null ? null : aiRecommendation.confidence(),
                aiRecommendation == null ? null : aiRecommendation.rationale(),

                policyDecision == null ? null : policyDecision.decision(),
                policyDecision == null ? null : policyDecision.reason(),
                execution.requiresHumanApproval(),

                execution.action(), execution.executed(), execution.executionStatus(),
                execution.provider(), execution.simulated(), execution.amountRecovered(),
                execution.failureCode(), execution.duplicate(),

                execution.paymentConfirmationStatus(), execution.confirmedAmount(),
                execution.providerPaymentId(), execution.confirmedAt(),

                buildSafetyExplanation(execution),
                timeline
        );
    }

    /** Composes a human-readable explanation from real response fields only — never invents new facts. */
    private String buildSafetyExplanation(RecoveryExecutionResponse execution) {
        if (execution.executionNote() != null) {
            return execution.executionNote();
        }
        PolicyDecision decision = execution.policyDecision() == null ? null : execution.policyDecision().decision();
        String reason = execution.policyDecision() == null ? null : execution.policyDecision().reason();

        // `executed=true` alone no longer means "this call itself just ran it" - a transaction that
        // already had a successful attempt (e.g. re-evaluating an already-recovered scenario) reports
        // `executed=true` honestly too (see RecoveryExecutionService.existingAttemptResponse), with
        // `duplicate=true` marking it as a replay of prior state rather than a fresh call this made.
        if (execution.executed() && execution.duplicate()) {
            String policyNote = reason == null ? "" : " Blocked by safety policy on this call: " + reason;
            if (execution.paymentConfirmationStatus() == PaymentConfirmationStatus.CONFIRMED) {
                return ("A prior recovery attempt for this transaction already executed and was confirmed by a "
                        + "verified payment webhook - amountRecovered is %s. Nothing new executed on this call."
                        + "%s").formatted(execution.amountRecovered(), policyNote);
            }
            return ("A prior recovery attempt for this transaction already executed through the %s payment "
                    + "provider (simulated=%s); nothing new executed on this call. Payment confirmation is "
                    + "still pending - amountRecovered stays 0.00 until a real, confirmed provider result exists."
                    + "%s").formatted(execution.provider(), execution.simulated(), policyNote);
        }
        if (execution.executed()) {
            return ("AI recommended an action; the policy engine authorized it (ALLOW) and it was executed "
                    + "through the %s payment provider (simulated=%s). This confirms the provider call ran — "
                    + "not that money was recovered. Payment confirmation is pending; amountRecovered stays "
                    + "0.00 until a real, confirmed provider result exists.").formatted(execution.provider(), execution.simulated());
        }
        if (decision == null) {
            return "This result reflects a resolved concurrent execution; see the recovery attempt for details.";
        }
        return switch (decision) {
            case ESCALATE -> "AI recommended an action, but the policy engine escalated it for human approval "
                    + "instead of executing automatically. Reason: " + reason;
            case BLOCK -> "The policy engine blocked this action before any execution could occur. "
                    + "Execution prevented by safety policy. Reason: " + reason;
            case STOP -> "The policy engine stopped automated recovery for this transaction. "
                    + "Execution prevented by safety policy. Reason: " + reason;
            // `provider` is set by RecoveryExecutionService.executedResponse() whenever a gateway
            // call was actually attempted, whether it succeeded or failed (see PaymentGateway's
            // failure() builders) - so its presence here, with executed()=false, means the call
            // was made and failed, not that this was some other, non-gateway action.
            case ALLOW -> execution.provider() != null
                    ? ("Policy authorized this action and it was attempted through the %s payment "
                            + "provider (simulated=%s), but the provider call failed. Reason: %s")
                            .formatted(execution.provider(), execution.simulated(),
                                    execution.failureReason() == null ? "no failure reason was reported." : execution.failureReason())
                    : "Policy authorized this action, but it is not a payment-gateway action, "
                            + "so no provider call was made.";
        };
    }

    // ---------------------------------------------------------------- aggregate metrics

    private RecoveryDemoSummaryResponse summarize(List<RecoveryDemoScenarioResponse> scenarios) {
        int allowed = 0, blocked = 0, escalated = 0, stopped = 0, executed = 0, gatewayCalls = 0, simulated = 0, atRisk = 0;
        BigDecimal totalAmountAtRisk = zero();
        BigDecimal totalPotentialRecoveryValue = zero();
        BigDecimal confirmedAmountRecovered = zero();

        for (RecoveryDemoScenarioResponse s : scenarios) {
            if (s.policyDecision() != null) {
                switch (s.policyDecision()) {
                    case ALLOW -> allowed++;
                    case BLOCK -> blocked++;
                    case ESCALATE -> escalated++;
                    case STOP -> stopped++;
                }
            }
            if (s.executed()) executed++;
            // A gateway call happened on THIS run exactly when a provider is attached and this
            // response is not a replay of a pre-existing duplicate/blocked attempt.
            if (s.provider() != null && !s.duplicate()) gatewayCalls++;
            if (s.simulated()) simulated++;
            if (s.amountAtRisk() != null && s.amountAtRisk().compareTo(BigDecimal.ZERO) > 0) atRisk++;

            totalAmountAtRisk = totalAmountAtRisk.add(nz(s.amountAtRisk()));
            totalPotentialRecoveryValue = totalPotentialRecoveryValue.add(nz(s.potentialRecoveryValue()));
            confirmedAmountRecovered = confirmedAmountRecovered.add(nz(s.amountRecovered()));
        }

        return new RecoveryDemoSummaryResponse(
                scenarios.size(), atRisk, allowed, blocked, escalated, stopped, executed,
                gatewayCalls, simulated, totalAmountAtRisk, totalPotentialRecoveryValue,
                confirmedAmountRecovered, scenarios);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
