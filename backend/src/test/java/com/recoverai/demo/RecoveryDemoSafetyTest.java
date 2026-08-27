package com.recoverai.demo;

import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RecoveryDemoScenarioResponse;
import com.recoverai.dto.RecoveryDemoSummaryResponse;
import com.recoverai.payment.PaymentGateway;
import com.recoverai.policy.RecoveryPolicyService;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.seed.DemoDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mandatory Phase 8 safety guarantees (spec section 11): every claim
 * here is checked against the real seeded demo data and the real
 * {@code RecoveryDemoService} -&gt; {@code RecoveryExecutionService} pipeline,
 * never against hardcoded/fabricated expectations.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryDemoSafetyTest {

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RecoveryDemoService recoveryDemoService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    @BeforeEach
    void setUp() {
        seeder.seed();
    }

    // ---------------------------------------------------------------- structural: cannot bypass policy or payment gateway

    @Test
    void recoveryDemoService_hasNoFieldOfTypePaymentGateway() {
        assertThat(hasFieldOfType(RecoveryDemoService.class, PaymentGateway.class)).isFalse();
    }

    @Test
    void recoveryDemoService_hasNoFieldOfTypeRecoveryPolicyService() {
        assertThat(hasFieldOfType(RecoveryDemoService.class, RecoveryPolicyService.class)).isFalse();
    }

    private static boolean hasFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (fieldType.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- behavioral: gateway invocation bounds

    @Test
    void highValueTransaction_aiMayRecommendRetry_policyEscalates_gatewayNeverCalled() {
        int before = attemptsFor("demo-high-value").size();
        recoveryDemoService.runOne("demo-high-value");
        assertThat(attemptsFor("demo-high-value")).hasSize(before);
    }

    @Test
    void repeatedFailureTransaction_policyStops_gatewayNeverCalled() {
        // This scenario is deliberately seeded WITH prior failed RecoveryAttempt rows (that
        // history is what makes the policy engine return STOP) - the guarantee under test is
        // that running the demo adds no NEW attempt, not that none pre-exist.
        int before = attemptsFor("demo-repeated-failure").size();
        recoveryDemoService.runOne("demo-repeated-failure");
        assertThat(attemptsFor("demo-repeated-failure")).hasSize(before);
    }

    @Test
    void alreadyRecoveredTransaction_policyBlocks_gatewayNeverCalled() {
        int before = attemptsFor("demo-successful-recovery").size();
        recoveryDemoService.runOne("demo-successful-recovery");
        assertThat(attemptsFor("demo-successful-recovery")).hasSize(before);
    }

    @Test
    void allowedEasyRecovery_gatewayCalledExactlyOnce() {
        int before = attemptsFor("demo-easy-recovery").size();

        RecoveryDemoScenarioResponse result = recoveryDemoService.runOne("demo-easy-recovery");
        assertThat(result.policyDecision()).isEqualTo(PolicyDecision.ALLOW);

        List<RecoveryAttempt> attempts = attemptsFor("demo-easy-recovery");
        assertThat(attempts).hasSize(before + 1);
        assertThat(attempts.get(attempts.size() - 1).getProvider()).isEqualTo("mock");
    }

    @Test
    void reRunningDemo_duplicateActionProtectionRemainsActive() {
        recoveryDemoService.runOne("demo-easy-recovery");
        int attemptsAfterFirstRun = attemptsFor("demo-easy-recovery").size();

        RecoveryDemoScenarioResponse secondRun = recoveryDemoService.runOne("demo-easy-recovery");

        // No new attempt row is created (the real safety guarantee) - `executed` honestly reports
        // that a prior attempt for this transaction genuinely succeeded, rather than "not executed".
        assertThat(attemptsFor("demo-easy-recovery")).hasSize(attemptsAfterFirstRun);
        assertThat(secondRun.executed()).isTrue();
        assertThat(secondRun.policyDecision()).isEqualTo(PolicyDecision.BLOCK);
    }

    // ---------------------------------------------------------------- behavioral: no fake recovered revenue

    @Test
    void executionStatusSuccess_aloneNeverTransitionsTransactionToRecovered() {
        recoveryDemoService.runOne("demo-easy-recovery");

        Transaction transaction = transactionRepository.findByExternalTransactionId("demo-easy-recovery").orElseThrow();
        assertThat(transaction.getStatus()).isNotEqualTo(TransactionStatus.RECOVERED);
    }

    @Test
    void amountRecovered_isZeroForEveryOutcomeExceptAGenuinelyConfirmedOne() {
        // ALREADY_RECOVERED is seeded as a historically confirmed recovery (₹1,899.00) - every
        // other scenario, none of which has ever been confirmed, must stay exactly zero.
        RecoveryDemoSummaryResponse summary = recoveryDemoService.runAll();
        for (RecoveryDemoScenarioResponse scenario : summary.scenarios()) {
            BigDecimal expected = "ALREADY_RECOVERED".equals(scenario.scenarioLabel())
                    ? new BigDecimal("1899.00") : BigDecimal.ZERO;
            assertThat(scenario.amountRecovered())
                    .as("amountRecovered for " + scenario.scenarioLabel())
                    .isEqualByComparingTo(expected);
        }
    }

    @Test
    void confirmedAmountRecovered_neverDerivedFromPotentialOrAtRiskFigures() {
        RecoveryDemoSummaryResponse summary = recoveryDemoService.runAll();

        // The only genuinely confirmed figure in the seeded demo dataset - never inflated by,
        // or equal to, the unrelated potential/at-risk estimates below.
        assertThat(summary.confirmedAmountRecovered()).isEqualByComparingTo(new BigDecimal("1899.00"));
        assertThat(summary.totalPotentialRecoveryValue()).isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.totalAmountAtRisk()).isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.confirmedAmountRecovered()).isNotEqualByComparingTo(summary.totalPotentialRecoveryValue());
        assertThat(summary.confirmedAmountRecovered()).isNotEqualByComparingTo(summary.totalAmountAtRisk());
    }

    // ---------------------------------------------------------------- helpers

    private List<RecoveryAttempt> attemptsFor(String externalTransactionId) {
        Transaction transaction = transactionRepository.findByExternalTransactionId(externalTransactionId).orElseThrow();
        return recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transaction.getId());
    }
}
