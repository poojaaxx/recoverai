package com.recoverai.policy;

import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.dto.RecoveryPolicyDecisionResponse;
import com.recoverai.repository.TransactionRepository;
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
 * Evaluates RETRY_PAYMENT against each of the five named demo transactions
 * and asserts the policy decision each is meant to demonstrate (see
 * DemoDataSeeder's comments on each demo transaction, and Phase 4 spec
 * section 23).
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryPolicyDemoScenariosTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryPolicyDemoScenariosTest.class);

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RecoveryPolicyService recoveryPolicyService;
    @Autowired
    private TransactionRepository transactionRepository;

    private SeedReport seedReport;

    @BeforeEach
    void setUp() {
        seedReport = seeder.seed();
    }

    @Test
    void demoEasyRecovery_retryPayment_isAllowed() {
        RecoveryPolicyDecisionResponse response = evaluate("easy_recovery", RecoveryAction.RETRY_PAYMENT);
        log(response);
        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(response.requiresHumanApproval()).isFalse();
    }

    @Test
    void demoHighValue_retryPayment_isEscalated() {
        RecoveryPolicyDecisionResponse response = evaluate("high_value_requires_approval", RecoveryAction.RETRY_PAYMENT);
        log(response);
        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void demoRetryEscalation_retryPayment_isEscalated_alreadyUnderReview() {
        RecoveryPolicyDecisionResponse response = evaluate("retry_then_escalation", RecoveryAction.RETRY_PAYMENT);
        log(response);
        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
    }

    @Test
    void demoRepeatedFailure_retryPayment_isStopped() {
        RecoveryPolicyDecisionResponse response = evaluate("repeated_failure_stopped", RecoveryAction.RETRY_PAYMENT);
        log(response);
        assertThat(response.decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.requiresHumanApproval()).isFalse();
    }

    @Test
    void demoSuccessfulRecovery_retryPayment_isBlocked() {
        RecoveryPolicyDecisionResponse response = evaluate("successful_recovery", RecoveryAction.RETRY_PAYMENT);
        log(response);
        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
    }

    private RecoveryPolicyDecisionResponse evaluate(String demoKey, RecoveryAction action) {
        UUID transactionId = transactionRepository.findByExternalTransactionId(seedReport.demoTransactionIds().get(demoKey))
                .orElseThrow().getId();
        return recoveryPolicyService.evaluate(transactionId, action);
    }

    private void log(RecoveryPolicyDecisionResponse response) {
        log.info("{}: action={} decision={} requiresHumanApproval={} reason={}",
                response.externalTransactionId(), response.action(), response.decision(),
                response.requiresHumanApproval(), response.reason());
    }
}
