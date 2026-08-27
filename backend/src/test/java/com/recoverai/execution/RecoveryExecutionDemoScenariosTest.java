package com.recoverai.execution;

import com.recoverai.domain.RecoveryAction;
import com.recoverai.dto.RecoveryExecutionResponse;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full Phase 7 pipeline (real {@link
 * com.recoverai.agent.MockAIRecoveryProvider}, real policy, real {@link
 * com.recoverai.payment.MockPaymentGateway}) against each of the 5 named
 * demo transactions, per the Phase 7 spec section 41 expected outcomes.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryExecutionDemoScenariosTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryExecutionDemoScenariosTest.class);

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RevenueRiskService revenueRiskService;
    @Autowired
    private RecoveryExecutionService recoveryExecutionService;
    @Autowired
    private TransactionRepository transactionRepository;

    private SeedReport seedReport;

    @BeforeEach
    void setUp() {
        seedReport = seeder.seed();
        revenueRiskService.analyzeAllAtRisk();
    }

    @Test
    void demoEasyRecovery_isAllowedAndExecutedViaMock() {
        RecoveryExecutionResponse response = execute("easy_recovery");
        log(response);

        assertThat(response.policyDecision().decision().name()).isEqualTo("ALLOW");
        assertThat(response.executed()).isTrue();
        assertThat(response.action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.provider()).isEqualTo("mock");
        assertThat(response.simulated()).isTrue();
        assertThat(response.amountRecovered()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }

    @Test
    void demoHighValue_escalated_noExecution() {
        RecoveryExecutionResponse response = execute("high_value_requires_approval");
        log(response);

        assertThat(response.policyDecision().decision().name()).isEqualTo("ESCALATE");
        assertThat(response.executed()).isFalse();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.recoveryAttemptId()).isNull();
    }

    @Test
    void demoRetryEscalation_escalated_noExecution() {
        RecoveryExecutionResponse response = execute("retry_then_escalation");
        log(response);

        assertThat(response.policyDecision().decision().name()).isEqualTo("ESCALATE");
        assertThat(response.executed()).isFalse();
        assertThat(response.recoveryAttemptId()).isNull();
    }

    @Test
    void demoRepeatedFailure_stopped_noExecution() {
        RecoveryExecutionResponse response = execute("repeated_failure_stopped");
        log(response);

        assertThat(response.policyDecision().decision().name()).isEqualTo("STOP");
        assertThat(response.executed()).isFalse();
        assertThat(response.recoveryAttemptId()).isNull();
    }

    @Test
    void demoSuccessfulRecovery_blocked_noNewExecution() {
        RecoveryExecutionResponse response = execute("successful_recovery");
        log(response);

        assertThat(response.policyDecision().decision().name()).isEqualTo("BLOCK");
        // Seeded as a historically SUCCESS + CONFIRMED attempt - re-evaluating it correctly
        // reports that real prior success (and points at the real attempt row) rather than
        // "not executed"; no NEW attempt is created by this call (see RecoveryDemoSafetyTest's
        // duplicate-protection tests for that guarantee).
        assertThat(response.executed()).isTrue();
        assertThat(response.recoveryAttemptId()).isNotNull();
        assertThat(response.paymentConfirmationStatus().name()).isEqualTo("CONFIRMED");
        assertThat(response.amountRecovered()).isEqualByComparingTo(new BigDecimal("1899.00"));
    }

    private RecoveryExecutionResponse execute(String demoKey) {
        UUID transactionId = transactionRepository.findByExternalTransactionId(seedReport.demoTransactionIds().get(demoKey))
                .orElseThrow().getId();
        return recoveryExecutionService.execute(transactionId);
    }

    private void log(RecoveryExecutionResponse response) {
        log.info("{}: decision={} executed={} action={} provider={} amountRecovered={}",
                response.externalTransactionId(), response.policyDecision() == null ? "n/a" : response.policyDecision().decision(),
                response.executed(), response.action(), response.provider(), response.amountRecovered());
    }
}
