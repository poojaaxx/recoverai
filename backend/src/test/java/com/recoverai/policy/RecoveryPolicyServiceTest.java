package com.recoverai.policy;

import com.recoverai.domain.Customer;
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
import com.recoverai.dto.RecoveryPolicyDecisionResponse;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic policy-decision coverage for the Phase 4 safety boundary.
 * Every test loads its facts through real persisted entities (never
 * constructs a decision from client-supplied data) to match how {@code
 * RecoveryPolicyService} is actually used.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryPolicyServiceTest {

    @Autowired
    private RecoveryPolicyService recoveryPolicyService;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private RevenueRiskRepository revenueRiskRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private Merchant merchant;
    private Customer customer;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Policy Test Merchant")
                .email("policy-" + UUID.randomUUID() + "@example.com")
                .build());
        customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Policy Test Customer")
                .email("policy-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(5)
                .failedPaymentCount(1)
                .build());
    }

    // ---------------------------------------------------------------- test data helpers

    private Transaction transaction(TransactionStatus status, BigDecimal amount, int attemptCount) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(amount)
                .currency("INR")
                .status(status)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode("BANK_DECLINED")
                .attemptCount(attemptCount)
                .build());
    }

    private void addAttempt(Transaction transaction, RecoveryAction action, RecoveryAttemptStatus status,
                             int attemptNumber, Instant executedAt) {
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(action)
                .status(status)
                .attemptNumber(attemptNumber)
                .amount(transaction.getAmount())
                .executedAt(executedAt)
                .build());
    }

    private void setRiskLevel(Transaction transaction, RiskLevel level) {
        revenueRiskRepository.save(RevenueRisk.builder()
                .transaction(transaction)
                .riskScore(new BigDecimal("90.00"))
                .recoveryProbability(new BigDecimal("0.5000"))
                .amountAtRisk(transaction.getAmount())
                .riskLevel(level)
                .factors(java.util.List.of())
                .build());
    }

    // ---------------------------------------------------------------- ALLOW

    @Test
    void freshFailedTransaction_retryPayment_isAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
        assertThat(response.requiresHumanApproval()).isFalse();
        assertThat(response.policyChecks()).isNotEmpty();
        assertThat(response.policyChecks()).allMatch(c -> c.passed());
    }

    @Test
    void freshFailedTransaction_createPaymentLinkAndSendReminder_areAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        assertThat(recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.CREATE_PAYMENT_LINK).decision())
                .isEqualTo(PolicyDecision.ALLOW);
        assertThat(recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.SEND_RECOVERY_REMINDER).decision())
                .isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void retryBelowLimit_isAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void amountExactlyAtThreshold_isAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("25000"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- BLOCK

    @Test
    void successfulTransaction_retryPayment_isBlocked() {
        Transaction txn = transaction(TransactionStatus.SUCCESS, new BigDecimal("999.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.requiresHumanApproval()).isFalse();
        assertThat(response.reason()).contains("completed successfully");
    }

    @Test
    void recoveredTransaction_retryPayment_isBlocked() {
        Transaction txn = transaction(TransactionStatus.RECOVERED, new BigDecimal("1899.00"), 2);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.reason()).contains("already been recovered");
    }

    @Test
    void pendingTransaction_retryPayment_isBlocked_noFailedPaymentToRetry() {
        Transaction txn = transaction(TransactionStatus.PENDING, new BigDecimal("1500.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
    }

    @Test
    void abandonedTransaction_retryPayment_isBlocked_noFailedPaymentToRetry() {
        Transaction txn = transaction(TransactionStatus.ABANDONED, new BigDecimal("1500.00"), 0);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
    }

    @Test
    void pendingTransaction_sendReminder_isAllowed() {
        Transaction txn = transaction(TransactionStatus.PENDING, new BigDecimal("1500.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.SEND_RECOVERY_REMINDER);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void duplicateAction_recentlySucceeded_isBlocked() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.SEND_RECOVERY_REMINDER, RecoveryAttemptStatus.SUCCESS, 1,
                Instant.now().minus(1, ChronoUnit.HOURS));

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.SEND_RECOVERY_REMINDER);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.reason()).contains("duplicate action prevented");
    }

    @Test
    void duplicateAction_outsideWindow_isAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.SEND_RECOVERY_REMINDER, RecoveryAttemptStatus.SUCCESS, 1,
                Instant.now().minus(48, ChronoUnit.HOURS));

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.SEND_RECOVERY_REMINDER);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void duplicateAction_priorFailedAttempt_isNotTreatedAsDuplicate() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- P1.2 cooldown

    private RecoveryPolicyService serviceWithCooldown(long minutes) {
        com.recoverai.config.RecoveryPolicyProperties properties = new com.recoverai.config.RecoveryPolicyProperties();
        properties.setMinCooldownMinutesBetweenActions(minutes);
        return new RecoveryPolicyService(transactionRepository, recoveryAttemptRepository, revenueRiskRepository,
                auditLogRepository, properties);
    }

    @Test
    void cooldown_disabledByDefault_recentActionOfAnyTypeDoesNotBlock() {
        // The real autowired service (min-cooldown-minutes-between-actions: 0) must behave exactly
        // as it always has - this is the "never breaks the demo" guarantee.
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.CREATE_PAYMENT_LINK);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void cooldown_enabled_recentActionOfAnyType_blocksWithoutStoppingTheTransaction() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());

        RecoveryPolicyDecisionResponse response = serviceWithCooldown(30)
                .evaluate(txn.getId(), RecoveryAction.CREATE_PAYMENT_LINK);

        // BLOCK, not STOP - a cooldown is temporary and must never persist a durable STOPPED status
        // (RecoveryExecutionService.applyLifecycleStatus only reacts to ESCALATE/STOP).
        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.reason()).contains("cooldown").contains("temporarily paused");
    }

    @Test
    void cooldown_enabled_windowElapsed_isAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1,
                Instant.now().minus(45, ChronoUnit.MINUTES));

        RecoveryPolicyDecisionResponse response = serviceWithCooldown(30)
                .evaluate(txn.getId(), RecoveryAction.CREATE_PAYMENT_LINK);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void cooldown_enabled_noPriorAttempts_isAllowed() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryPolicyDecisionResponse response = serviceWithCooldown(30)
                .evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- Phase 14 customer consent

    @Test
    void optedOutCustomer_retryPayment_isBlocked() {
        customer.setRecoveryContactAllowed(false);
        customer = customerRepository.save(customer);
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.reason()).contains("opted out");
    }

    @Test
    void optedOutCustomer_sendReminder_isBlocked_aiCannotOverride() {
        // Even the specifically-outreach action (SEND_RECOVERY_REMINDER) is blocked - not just
        // payment retries - and no AI recommendation can bypass this deterministic check.
        customer.setRecoveryContactAllowed(false);
        customer = customerRepository.save(customer);
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.SEND_RECOVERY_REMINDER);

        assertThat(response.decision()).isEqualTo(PolicyDecision.BLOCK);
        assertThat(response.policyChecks()).anyMatch(c -> c.name().equals("CUSTOMER_CONSENT") && !c.passed());
    }

    @Test
    void optedInCustomer_isUnaffected() {
        assertThat(customer.isRecoveryContactAllowed()).isTrue();
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- ESCALATE

    @Test
    void amountAboveThreshold_isEscalated_requiresApproval() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("47500.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.reason()).contains("exceeds the autonomous recovery limit");
    }

    @Test
    void amountJustAboveThreshold_isEscalated() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("25000.01"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
    }

    @Test
    void alreadyEscalatedTransaction_retryPayment_returnsEscalate() {
        Transaction txn = transaction(TransactionStatus.ESCALATED, new BigDecimal("3499.00"), 2);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void explicitEscalateAction_onFailedTransaction_isHonored() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("999.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.ESCALATE);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void criticalRiskLevel_forcesEscalation_evenWithinAmountLimit() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("999.00"), 1);
        setRiskLevel(txn, RiskLevel.CRITICAL);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ESCALATE);
        assertThat(response.requiresHumanApproval()).isTrue();
    }

    @Test
    void nonCriticalRiskLevel_doesNotForceEscalation() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("999.00"), 1);
        setRiskLevel(txn, RiskLevel.LOW);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- STOP

    @Test
    void stoppedTransaction_retryPayment_returnsStop() {
        Transaction txn = transaction(TransactionStatus.STOPPED, new BigDecimal("7499.00"), 2);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.requiresHumanApproval()).isFalse();
    }

    @Test
    void explicitStopAction_isHonored() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("999.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.STOP);

        assertThat(response.decision()).isEqualTo(PolicyDecision.STOP);
    }

    @Test
    void retryLimitReached_stillFailedStatus_returnsStop_maxAttemptsPlusOne() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 3);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 2, Instant.now());

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.reason()).contains("Maximum automatic retry attempts");
    }

    @Test
    void retryLimitExactlyAtMax_isAllowed_onlyExceedingStops() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 2);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void repeatedFailure_mixedActionsReachTotalCap_returnsStop() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 3);
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());
        addAttempt(txn, RecoveryAction.SEND_RECOVERY_REMINDER, RecoveryAttemptStatus.FAILED, 2, Instant.now());
        addAttempt(txn, RecoveryAction.CREATE_PAYMENT_LINK, RecoveryAttemptStatus.FAILED, 3, Instant.now());

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.SEND_RECOVERY_REMINDER);

        assertThat(response.decision()).isEqualTo(PolicyDecision.STOP);
        assertThat(response.reason()).contains("Maximum recovery actions per transaction");
    }

    // ---------------------------------------------------------------- edge cases / safety

    @Test
    void unknownTransactionId_throwsNotFound() {
        assertThatThrownBy(() -> recoveryPolicyService.evaluate(UUID.randomUUID(), RecoveryAction.RETRY_PAYMENT))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void noRecoveryHistory_allChecksStillEvaluateSafely() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("1000.00"), 1);

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void missingRevenueRiskRow_doesNotFailEvaluation() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("1000.00"), 1);
        assertThat(revenueRiskRepository.findByTransactionId(txn.getId())).isEmpty();

        RecoveryPolicyDecisionResponse response = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(response.decision()).isEqualTo(PolicyDecision.ALLOW);
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void evaluatingSameTransactionTwice_producesIdenticalDecision() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryPolicyDecisionResponse first = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);
        RecoveryPolicyDecisionResponse second = recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        assertThat(second.decision()).isEqualTo(first.decision());
        assertThat(second.requiresHumanApproval()).isEqualTo(first.requiresHumanApproval());
        assertThat(second.reason()).isEqualTo(first.reason());
        assertThat(second.policyChecks()).isEqualTo(first.policyChecks());
    }

    // ---------------------------------------------------------------- audit trail (section 21)

    @Test
    void repeatedIdenticalEvaluation_writesOnlyOneAuditRow() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);
        recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);
        recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        long auditCount = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .filter(a -> "RECOVERY_POLICY_EVALUATED".equals(a.getEventType()))
                .count();
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void changingDecisionBetweenEvaluations_writesANewAuditRow() {
        Transaction txn = transaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT); // ALLOW
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 1, Instant.now());
        addAttempt(txn, RecoveryAction.RETRY_PAYMENT, RecoveryAttemptStatus.FAILED, 2, Instant.now());
        recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT); // STOP (retry limit reached)

        long auditCount = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .filter(a -> "RECOVERY_POLICY_EVALUATED".equals(a.getEventType()))
                .count();
        assertThat(auditCount).isEqualTo(2);
    }

    @Test
    void auditRow_capturesDecisionAndActor() {
        Transaction txn = transaction(TransactionStatus.RECOVERED, new BigDecimal("1899.00"), 2);

        recoveryPolicyService.evaluate(txn.getId(), RecoveryAction.RETRY_PAYMENT);

        var audit = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .filter(a -> "RECOVERY_POLICY_EVALUATED".equals(a.getEventType()))
                .findFirst().orElseThrow();
        assertThat(audit.getActor()).isEqualTo("POLICY_ENGINE");
        assertThat(audit.getDecision()).isEqualTo("BLOCK");
        assertThat(audit.getReason()).isNotBlank();
    }
}
