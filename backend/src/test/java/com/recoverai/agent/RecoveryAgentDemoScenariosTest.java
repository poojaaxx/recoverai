package com.recoverai.agent;

import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.dto.RecoveryAgentEvaluationResponse;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.RevenueRiskService;
import com.recoverai.seed.DemoDataSeeder;
import com.recoverai.seed.SeedReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full context -&gt; AI recommendation -&gt; policy decision
 * pipeline against each of the 5 named demo transactions, after a real
 * Phase 3 risk analysis pass (so the AI context includes genuine
 * computed risk data, not the Phase 2 seed heuristic). Nothing here is
 * asserted by forcing the agent's internals - every expectation follows
 * from the same real transaction/customer/attempt state {@code
 * RecoveryPolicyDemoScenariosTest} already exercises against Phase 4
 * directly; this test additionally confirms the AI's own recommendation
 * and that policy remains authoritative when it disagrees.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryAgentDemoScenariosTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryAgentDemoScenariosTest.class);

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RevenueRiskService revenueRiskService;
    @Autowired
    private RecoveryAgentService recoveryAgentService;
    @Autowired
    private TransactionRepository transactionRepository;

    private SeedReport seedReport;

    @BeforeEach
    void setUp() {
        seedReport = seeder.seed();
        revenueRiskService.analyzeAllAtRisk();
    }

    @Test
    void demoEasyRecovery_aiRecommendsRetry_policyAllows() {
        RecoveryAgentEvaluationResponse response = evaluate("easy_recovery");
        log(response);
        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
    }

    @Test
    void demoHighValue_policyEscalatesOnAmount_regardlessOfAiChoice() {
        RecoveryAgentEvaluationResponse response = evaluate("high_value_requires_approval");
        log(response);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void demoRetryEscalation_policyPreventsAutonomousRetry() {
        RecoveryAgentEvaluationResponse response = evaluate("retry_then_escalation");
        log(response);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.finalAction()).isNotEqualTo(RecoveryAction.RETRY_PAYMENT);
    }

    @Test
    void demoRepeatedFailure_aiAvoidsFurtherRetries_policyStops() {
        RecoveryAgentEvaluationResponse response = evaluate("repeated_failure_stopped");
        log(response);
        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.STOP);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.STOP);
    }

    @Test
    void demoSuccessfulRecovery_aiDoesNotRecommendExecutingAnotherRecovery_policyBlocks() {
        RecoveryAgentEvaluationResponse response = evaluate("successful_recovery");
        log(response);
        assertThat(response.aiRecommendation().action()).isNotIn(RecoveryAction.RETRY_PAYMENT,
                RecoveryAction.CREATE_PAYMENT_LINK, RecoveryAction.SEND_RECOVERY_REMINDER);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.finalAction()).isNull();
    }

    private RecoveryAgentEvaluationResponse evaluate(String demoKey) {
        UUID transactionId = transactionRepository.findByExternalTransactionId(seedReport.demoTransactionIds().get(demoKey))
                .orElseThrow().getId();
        return recoveryAgentService.evaluate(transactionId);
    }

    private void log(RecoveryAgentEvaluationResponse response) {
        log.info("{}: aiAction={} confidence={} policyDecision={} finalAction={} requiresHumanApproval={}",
                response.externalTransactionId(), response.aiRecommendation().action(),
                response.aiRecommendation().confidence(), response.policyDecision().decision(),
                response.finalAction(), response.requiresHumanApproval());
    }
}
