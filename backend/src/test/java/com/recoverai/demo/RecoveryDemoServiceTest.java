package com.recoverai.demo;

import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.dto.RecoveryDemoScenarioResponse;
import com.recoverai.dto.RecoveryDemoSummaryResponse;
import com.recoverai.execution.RecoveryExecutionService;
import com.recoverai.payment.PaymentExecutionResult;
import com.recoverai.payment.PaymentFailureReason;
import com.recoverai.payment.PaymentGateway;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.RevenueRiskService;
import com.recoverai.seed.DemoDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Phase 8 demo service against the five fixed scenarios (spec
 * section 2), reusing the real seeded demo data and the real Phase 3-7
 * pipeline — no scenario outcome is hardcoded here independently of what
 * the real services actually decide.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryDemoServiceTest {

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RecoveryDemoService recoveryDemoService;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private RevenueRiskService revenueRiskService;
    @Autowired
    private RecoveryAgentService recoveryAgentService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        seeder.seed();
    }

    @Test
    void runAll_returnsFiveScenariosWithExpectedOutcomes() {
        RecoveryDemoSummaryResponse summary = recoveryDemoService.runAll();

        assertThat(summary.scenariosEvaluated()).isEqualTo(5);
        assertThat(summary.scenarios()).hasSize(5);

        RecoveryDemoScenarioResponse easy = find(summary, "EASY_RECOVERY");
        assertThat(easy.policyDecision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(easy.executed()).isTrue();
        assertThat(easy.finalAction()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(easy.provider()).isEqualTo("mock");
        assertThat(easy.simulated()).isTrue();
        assertThat(easy.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);

        RecoveryDemoScenarioResponse highValue = find(summary, "HIGH_VALUE_ESCALATION");
        assertThat(highValue.policyDecision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(highValue.executed()).isFalse();
        assertThat(highValue.requiresHumanApproval()).isTrue();

        RecoveryDemoScenarioResponse repeatedFailure = find(summary, "REPEATED_FAILURE_STOP");
        assertThat(repeatedFailure.policyDecision()).isEqualTo(PolicyDecision.STOP);
        assertThat(repeatedFailure.executed()).isFalse();

        RecoveryDemoScenarioResponse alreadyRecovered = find(summary, "ALREADY_RECOVERED");
        assertThat(alreadyRecovered.policyDecision()).isEqualTo(PolicyDecision.BLOCK);
        // Seeded as a historically SUCCESS + CONFIRMED attempt - re-evaluating it (this call
        // included) correctly reports that real prior success, not "not executed".
        assertThat(alreadyRecovered.executed()).isTrue();
        assertThat(alreadyRecovered.paymentConfirmationStatus().name()).isEqualTo("CONFIRMED");
        assertThat(alreadyRecovered.amountRecovered()).isEqualByComparingTo(new BigDecimal("1899.00"));

        RecoveryDemoScenarioResponse alreadyEscalated = find(summary, "ALREADY_ESCALATED");
        assertThat(alreadyEscalated.policyDecision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(alreadyEscalated.executed()).isFalse();
        assertThat(alreadyEscalated.requiresHumanApproval()).isTrue();
    }

    @Test
    void runAll_auditTimelineIsNeverEmptyForAnyScenario() {
        RecoveryDemoSummaryResponse summary = recoveryDemoService.runAll();
        for (RecoveryDemoScenarioResponse scenario : summary.scenarios()) {
            assertThat(scenario.auditTimeline()).as("audit timeline for " + scenario.scenarioLabel()).isNotEmpty();
        }
    }

    @Test
    void runAll_confirmedAmountRecoveredOnlyReflectsGenuinelyConfirmedAttempts() {
        // ALREADY_RECOVERED is seeded as a historically confirmed recovery (₹1,899.00) - the
        // other 4 scenarios have never been confirmed, so contribute nothing. The aggregate must
        // equal exactly the one genuine confirmation, never more (never derived from potential/
        // at-risk figures) and never less (never hidden just because it happened in the past).
        RecoveryDemoSummaryResponse summary = recoveryDemoService.runAll();
        assertThat(summary.confirmedAmountRecovered()).isEqualByComparingTo(new BigDecimal("1899.00"));
        for (RecoveryDemoScenarioResponse scenario : summary.scenarios()) {
            BigDecimal expected = "ALREADY_RECOVERED".equals(scenario.scenarioLabel())
                    ? new BigDecimal("1899.00") : BigDecimal.ZERO;
            assertThat(scenario.amountRecovered())
                    .as("amountRecovered for " + scenario.scenarioLabel())
                    .isEqualByComparingTo(expected);
        }
    }

    @Test
    void runAll_aggregateCountsMatchPerScenarioDecisions() {
        RecoveryDemoSummaryResponse summary = recoveryDemoService.runAll();

        assertThat(summary.allowedCount()).isEqualTo(1);
        assertThat(summary.escalatedCount()).isEqualTo(2);
        assertThat(summary.stoppedCount()).isEqualTo(1);
        assertThat(summary.blockedCount()).isEqualTo(1);
        // executed=2 (EASY_RECOVERY, freshly executed this call, + ALREADY_RECOVERED, honestly
        // reporting its real historical success) - but gatewayCalls stays 1, since a real gateway
        // call happened only for EASY_RECOVERY; ALREADY_RECOVERED is a replay of prior state.
        assertThat(summary.executedCount()).isEqualTo(2);
        assertThat(summary.gatewayCalls()).isEqualTo(1);
        assertThat(summary.simulatedExecutions()).isEqualTo(2);
    }

    @Test
    void runningDemoTwice_doesNotCreateAdditionalRecoveryAttemptsAndStaysBlockedByPolicy() {
        // The wider seeded dataset (bulk transactions, plus historical attempts pre-seeded
        // for the repeated-failure/successful-recovery scenarios) already contains many
        // RecoveryAttempt rows, so this asserts the DELTA the demo itself adds, not a total.
        long baseline = recoveryAttemptRepository.count();

        recoveryDemoService.runAll();
        long attemptsAfterFirstRun = recoveryAttemptRepository.count();
        assertThat(attemptsAfterFirstRun - baseline).isEqualTo(1); // only the easy-recovery scenario ever executes

        RecoveryDemoSummaryResponse secondRun = recoveryDemoService.runAll();
        long attemptsAfterSecondRun = recoveryAttemptRepository.count();

        assertThat(attemptsAfterSecondRun).isEqualTo(attemptsAfterFirstRun);
        assertThat(secondRun.gatewayCalls()).isZero();
        // executed=2 (EASY_RECOVERY + ALREADY_RECOVERED) even though neither made a fresh gateway
        // call this run (gatewayCalls stays 0, asserted above) - both honestly report a real prior
        // success rather than "not executed" just because nothing NEW happened on this call.
        assertThat(secondRun.executedCount()).isEqualTo(2);

        RecoveryDemoScenarioResponse easySecondTime = find(secondRun, "EASY_RECOVERY");
        assertThat(easySecondTime.executed()).isTrue();
        assertThat(easySecondTime.policyDecision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(easySecondTime.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO); // never confirmed in this test
        assertThat(easySecondTime.safetyExplanation()).containsIgnoringCase("safety policy");
    }

    @Test
    void gatewayCallFailure_onAllowedPaymentAction_explainsTheFailure_notMisreportedAsNonGatewayAction() {
        // Regression test: buildSafetyExplanation()'s ALLOW branch used to unconditionally say
        // "it is not a payment-gateway action, so no provider call was made" whenever
        // executed()=false under policy ALLOW - which is only true for SEND_RECOVERY_REMINDER.
        // For a genuine payment-gateway action (RETRY_PAYMENT/CREATE_PAYMENT_LINK) whose provider
        // call itself failed, executed() is also false, but a provider call absolutely was made -
        // this was observed live against a real Razorpay HTTP 400 failure.
        PaymentGateway alwaysDeclines = req -> new PaymentExecutionResult(false, "mock", null, req.transactionId(),
                req.action(), req.amount(), req.currency(), BigDecimal.ZERO, true, "failed",
                PaymentFailureReason.DECLINED, "Mock provider simulated a decline for this request.",
                req.idempotencyKey(), Instant.now(), null);
        RecoveryExecutionService failingExecutionService = new RecoveryExecutionService(
                transactionRepository, recoveryAttemptRepository, auditLogRepository,
                recoveryAgentService, alwaysDeclines, transactionManager);
        RecoveryDemoService demoServiceWithFailingGateway = new RecoveryDemoService(
                transactionRepository, auditLogRepository, revenueRiskService, failingExecutionService);

        RecoveryDemoScenarioResponse result = demoServiceWithFailingGateway.runOne("demo-easy-recovery");

        assertThat(result.policyDecision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(result.executed()).isFalse();
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.safetyExplanation())
                .contains("provider call failed")
                .doesNotContain("not a payment-gateway action");
    }

    @Test
    void runOne_unknownExternalId_throwsNotFound() {
        assertThatThrownBy(() -> recoveryDemoService.runOne("not-a-real-demo-transaction"))
                .isInstanceOf(DemoScenarioNotFoundException.class);
    }

    @Test
    void runOne_matchesTheCorrespondingEntryFromRunAll() {
        RecoveryDemoScenarioResponse single = recoveryDemoService.runOne("demo-easy-recovery");
        assertThat(single.externalTransactionId()).isEqualTo("demo-easy-recovery");
        assertThat(single.scenarioLabel()).isEqualTo("EASY_RECOVERY");
    }

    private static RecoveryDemoScenarioResponse find(RecoveryDemoSummaryResponse summary, String label) {
        return summary.scenarios().stream()
                .filter(s -> s.scenarioLabel().equals(label))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("No scenario labeled " + label));
    }
}
