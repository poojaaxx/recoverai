package com.recoverai.agent;

import com.recoverai.config.RecoveryPolicyProperties;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.InterventionType;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.domain.Urgency;
import com.recoverai.dto.RecoveryAgentEvaluationResponse;
import com.recoverai.policy.RecoveryPolicyService;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full context -&gt; AI recommendation -&gt; policy decision pipeline
 * coverage. Most tests exercise the real, deterministic {@link
 * MockAIRecoveryProvider} via the Spring-managed {@link
 * RecoveryAgentService} bean (the same wiring the API uses). Tests that
 * need to force a specific AI output - failure, malformed output, or a
 * deliberate AI-vs-policy mismatch (section 24) - construct a throwaway
 * {@code RecoveryAgentService} with a stub {@link AIRecoveryProvider} and
 * the same real, Spring-managed repositories/policy service.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecoveryAgentServiceTest {

    @Autowired
    private RecoveryAgentService recoveryAgentService;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private RevenueRiskRepository revenueRiskRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private RecoveryPolicyService recoveryPolicyService;
    @Autowired
    private RecoveryPolicyProperties recoveryPolicyProperties;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Agent Test Merchant")
                .email("agent-" + UUID.randomUUID() + "@example.com")
                .build());
    }

    // ---------------------------------------------------------------- test data helpers

    private Customer customer(int successCount, int failedCount) {
        return customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Customer " + UUID.randomUUID())
                .email("cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(successCount)
                .failedPaymentCount(failedCount)
                .build());
    }

    private Transaction transaction(Customer customer, TransactionStatus status, BigDecimal amount,
                                     FailureCategory failureCategory, int attemptCount) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("agent_txn_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(amount)
                .currency("INR")
                .status(status)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(failureCategory == null ? null : failureCategory.name())
                .attemptCount(attemptCount)
                .build());
    }

    private void addAttempt(Transaction transaction, RecoveryAction action, RecoveryAttemptStatus status, int attemptNumber) {
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(action)
                .status(status)
                .attemptNumber(attemptNumber)
                .amount(transaction.getAmount())
                .executedAt(Instant.now())
                .build());
    }

    private void setRisk(Transaction transaction, RiskLevel level, BigDecimal recoveryProbability) {
        revenueRiskRepository.save(RevenueRisk.builder()
                .transaction(transaction)
                .riskScore(new BigDecimal("50.00"))
                .recoveryProbability(recoveryProbability)
                .amountAtRisk(transaction.getAmount())
                .riskLevel(level)
                .factors(List.of())
                .build());
    }

    private RecoveryAgentService agentWithStubProvider(AIRecoveryProvider stub) {
        return new RecoveryAgentService(transactionRepository, recoveryAttemptRepository, revenueRiskRepository,
                auditLogRepository, recoveryPolicyService, recoveryPolicyProperties, stub);
    }

    // ---------------------------------------------------------------- 1. easy recovery

    @Test
    void easyRecovery_strongHistoryFewAttempts_recommendsRetryAndIsAllowed() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.requiresHumanApproval()).isFalse();
        assertThat(response.auditEventId()).isNotNull();
    }

    // ---------------------------------------------------------------- 2. high value

    @Test
    void highValue_exceedsAutonomousLimit_isEscalatedRegardlessOfAiRecommendation() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"),
                FailureCategory.INSUFFICIENT_FUNDS, 1);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    // ---------------------------------------------------------------- 3. repeated failure (still FAILED)

    @Test
    void repeatedFailure_manyPriorAttempts_aiRecommendsStop() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.FAILED, new BigDecimal("3499.00"),
                FailureCategory.BANK_DECLINED, 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1);
        addAttempt(txn, RecoveryAction.SEND_RECOVERY_REMINDER, RecoveryAttemptStatus.FAILED, 2);
        addAttempt(txn, RecoveryAction.CREATE_PAYMENT_LINK, RecoveryAttemptStatus.FAILED, 3);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 4);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.STOP);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.STOP);
    }

    // ---------------------------------------------------------------- 4. stopped transaction

    @Test
    void stoppedTransaction_recommendsStop_andIsBlockedFromReopening() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.STOPPED, new BigDecimal("7499.00"),
                FailureCategory.BANK_DECLINED, 2);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.STOP);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.STOP);
    }

    // ---------------------------------------------------------------- 5. recovered transaction

    @Test
    void recoveredTransaction_isBlocked_finalActionIsNull() {
        Customer strong = customer(5, 0);
        Transaction txn = transaction(strong, TransactionStatus.RECOVERED, new BigDecimal("1899.00"),
                FailureCategory.NETWORK_ERROR, 2);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.finalAction()).isNull();
        assertThat(response.requiresHumanApproval()).isFalse();
    }

    // ---------------------------------------------------------------- 6. pending transaction

    @Test
    void pendingTransaction_recommendsReminder_isAllowed() {
        Customer c = customer(3, 1);
        Transaction txn = transaction(c, TransactionStatus.PENDING, new BigDecimal("1500.00"), null, 1);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.SEND_RECOVERY_REMINDER);
        assertThat(response.aiRecommendation().interventionType()).isEqualTo(InterventionType.REENGAGE);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.SEND_RECOVERY_REMINDER);
    }

    // ---------------------------------------------------------------- 7. abandoned transaction

    @Test
    void abandonedTransaction_recommendsPaymentLink_isAllowed() {
        Customer c = customer(3, 1);
        Transaction txn = transaction(c, TransactionStatus.ABANDONED, new BigDecimal("1500.00"), null, 0);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.CREATE_PAYMENT_LINK);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- 8. critical risk

    @Test
    void criticalRisk_aiRecommendsEscalate_policyAgrees() {
        Customer c = customer(5, 1);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.BANK_DECLINED, 1);
        setRisk(txn, RiskLevel.CRITICAL, new BigDecimal("0.4000"));

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    // ---------------------------------------------------------------- 9. malformed AI output

    @Test
    void malformedAiOutput_fallsBackSafely_toEscalate() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider malformed = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), null, new BigDecimal("0.5"), "missing action",
                InterventionType.RETRY, BigDecimal.TEN, Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(malformed).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.aiRecommendation().providerAvailable()).isTrue();
        assertThat(response.aiRecommendation().rationale()).contains("failed validation");
    }

    // ---------------------------------------------------------------- 10. provider failure

    @Test
    void providerThrows_fallsBackSafely_noExceptionPropagates() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider failing = context -> {
            throw new AIProviderException("simulated network failure");
        };

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(failing).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.aiRecommendation().providerAvailable()).isFalse();
        assertThat(response.aiRecommendation().rationale()).contains("unavailable");
    }

    // ---------------------------------------------------------------- 11. invalid action (null)

    @Test
    void invalidAction_null_isTreatedAsMalformed() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider noAction = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), null, new BigDecimal("0.5"), "no action given",
                InterventionType.RETRY, BigDecimal.ONE, Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(noAction).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
    }

    // ---------------------------------------------------------------- 12/13. confidence boundaries

    @Test
    void confidenceBoundaryZero_isAccepted_notTreatedAsInvalid() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider zeroConfidence = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, BigDecimal.ZERO,
                "no confidence but still a valid recommendation", InterventionType.RETRY, BigDecimal.ZERO, Urgency.LOW,
                "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(zeroConfidence).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.aiRecommendation().confidence()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.aiRecommendation().providerAvailable()).isTrue();
    }

    @Test
    void confidenceBoundaryOne_isAccepted_notTreatedAsInvalid() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider fullConfidence = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, BigDecimal.ONE,
                "fully confident", InterventionType.RETRY, BigDecimal.ZERO, Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(fullConfidence).evaluate(txn.getId());

        assertThat(response.aiRecommendation().confidence()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(response.aiRecommendation().providerAvailable()).isTrue();
    }

    @Test
    void confidenceOutOfRange_isRejected_fallsBackSafely() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider tooConfident = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("1.5"),
                "overconfident", InterventionType.RETRY, BigDecimal.ZERO, Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(tooConfident).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.aiRecommendation().rationale()).contains("failed validation");
    }

    // ---------------------------------------------------------------- 14. expectedRecoveryValue = 0

    @Test
    void expectedRecoveryValueZero_isAccepted() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider zeroValue = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.5"),
                "low value recovery", InterventionType.RETRY, BigDecimal.ZERO, Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(zeroValue).evaluate(txn.getId());

        assertThat(response.expectedRecoveryValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.aiRecommendation().providerAvailable()).isTrue();
    }

    @Test
    void negativeExpectedRecoveryValue_isRejected() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider negativeValue = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.5"),
                "negative value", InterventionType.RETRY, new BigDecimal("-1"), Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(negativeValue).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
    }

    // ---------------------------------------------------------------- 15. duplicate recommendation (determinism)

    @Test
    void evaluatingSameTransactionTwice_producesIdenticalRecommendation() {
        Customer c = customer(6, 1);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RecoveryAgentEvaluationResponse first = recoveryAgentService.evaluate(txn.getId());
        RecoveryAgentEvaluationResponse second = recoveryAgentService.evaluate(txn.getId());

        assertThat(second.aiRecommendation().action()).isEqualTo(first.aiRecommendation().action());
        assertThat(second.aiRecommendation().confidence()).isEqualByComparingTo(first.aiRecommendation().confidence());
        assertThat(second.aiRecommendation().rationale()).isEqualTo(first.aiRecommendation().rationale());
        assertThat(second.policyDecision().decision()).isEqualTo(first.policyDecision().decision());
        assertThat(second.finalAction()).isEqualTo(first.finalAction());
    }

    // ---------------------------------------------------------------- 16. policy override (section 24 - critical)

    @Test
    void aiRecommendsRetry_policySaysStop_finalActionIsStop_noExecution() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 3);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 2);

        AIRecoveryProvider alwaysRetries = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.9"),
                "AI still recommends retrying", InterventionType.RETRY, new BigDecimal("2000"), Urgency.MEDIUM,
                "test", null);

        long attemptsBefore = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId()).size();

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(alwaysRetries).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.STOP);
        assertThat(response.finalAction()).isNotEqualTo(RecoveryAction.RETRY_PAYMENT);

        // No execution occurred: RecoveryAgentService never writes a RecoveryAttempt row.
        long attemptsAfter = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId()).size();
        assertThat(attemptsAfter).isEqualTo(attemptsBefore);
    }

    @Test
    void aiRecommendsRetry_policySaysEscalate_requiresHumanApprovalTrue() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"),
                FailureCategory.INSUFFICIENT_FUNDS, 1);

        AIRecoveryProvider alwaysRetries = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.8"),
                "AI recommends retrying despite the amount", InterventionType.RETRY, new BigDecimal("30000"),
                Urgency.MEDIUM, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(alwaysRetries).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.finalAction()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    // ---------------------------------------------------------------- 17. mismatched transactionId (Phase 10)

    @Test
    void mismatchedTransactionId_isRejected_fallsBackSafely() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        AIRecoveryProvider wrongTransactionId = context -> new RecoveryRecommendation(
                UUID.randomUUID(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.9"),
                "recommendation for a different transaction entirely", InterventionType.RETRY,
                BigDecimal.TEN, Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(wrongTransactionId).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.ESCALATE);
        assertThat(response.aiRecommendation().rationale()).contains("failed validation");
    }

    // ---------------------------------------------------------------- 18. CREATE_PAYMENT_LINK on an already-RECOVERED transaction (Phase 10)

    @Test
    void aiRecommendsPaymentLink_transactionAlreadyRecovered_isBlockedRegardless() {
        Customer strong = customer(5, 0);
        Transaction txn = transaction(strong, TransactionStatus.RECOVERED, new BigDecimal("1899.00"),
                FailureCategory.NETWORK_ERROR, 2);

        AIRecoveryProvider alwaysPaymentLink = context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.CREATE_PAYMENT_LINK, new BigDecimal("0.7"),
                "AI still recommends a payment link", InterventionType.RETRY, new BigDecimal("500"),
                Urgency.LOW, "test", null);

        RecoveryAgentEvaluationResponse response = agentWithStubProvider(alwaysPaymentLink).evaluate(txn.getId());

        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.CREATE_PAYMENT_LINK);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.finalAction()).isNull();
    }

    // ---------------------------------------------------------------- misc

    @Test
    void unknownTransactionId_throwsNotFound() {
        assertThatThrownBy(() -> recoveryAgentService.evaluate(UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void expectedRecoveryValue_isDistinctFromRiskProbabilityFields() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RecoveryAgentEvaluationResponse response = recoveryAgentService.evaluate(txn.getId());

        assertThat(response.aiRecommendation().confidence())
                .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(response.expectedRecoveryValue()).isNotNull();
    }

    @Test
    void auditEvent_isWritten_withActorAiAgent() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        recoveryAgentService.evaluate(txn.getId());

        var events = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .filter(a -> "RECOVERY_AI_RECOMMENDATION".equals(a.getEventType()))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActor()).isEqualTo("AI_AGENT");
        assertThat(events.get(0).getMetadata()).containsKeys("provider", "action", "confidence");
    }
}
