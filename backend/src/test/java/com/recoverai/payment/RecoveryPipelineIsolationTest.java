package com.recoverai.payment;

import com.recoverai.agent.AIRecoveryProvider;
import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.agent.RecoveryRecommendation;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mandatory Phase 6 architectural safety test: proves the AI
 * recommendation, the Phase 4 policy decision, and payment execution are
 * genuinely separate, both structurally and behaviorally.
 * <p>
 * "Structurally separate" is proven by reflection: neither {@code
 * RecoveryAgentService} nor {@code RecoveryPolicyService} declares any
 * field of type {@link PaymentGateway} - it is not merely untested that
 * they call it, it is impossible for them to, since they hold no
 * reference to it at all.
 * <p>
 * "Behaviorally separate" is proven with a counting {@link PaymentGateway}
 * wrapper around the real bean: only when {@code policyDecision.decision()
 * == ALLOW} does a (hypothetical, future Phase 7) caller invoke the
 * gateway - this test plays that caller's role itself, deliberately,
 * rather than adding a new orchestration service (see docs/ARCHITECTURE.md
 * for why that wiring is Phase 7's responsibility, not Phase 6's).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecoveryPipelineIsolationTest {

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
    private PaymentGateway realPaymentGateway;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Isolation Test Merchant")
                .email("isolation-" + UUID.randomUUID() + "@example.com")
                .build());
    }

    // ---------------------------------------------------------------- structural separation

    @Test
    void recoveryAgentService_hasNoFieldOfTypePaymentGateway() {
        assertThat(hasFieldOfType(RecoveryAgentService.class, PaymentGateway.class)).isFalse();
    }

    @Test
    void recoveryPolicyService_hasNoFieldOfTypePaymentGateway() {
        assertThat(hasFieldOfType(RecoveryPolicyService.class, PaymentGateway.class)).isFalse();
    }

    private static boolean hasFieldOfType(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (fieldType.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- behavioral separation

    private Customer customer(int successCount, int failedCount) {
        return customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Customer " + UUID.randomUUID())
                .email("cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(successCount)
                .failedPaymentCount(failedCount)
                .build());
    }

    private Transaction transaction(Customer customer, TransactionStatus status, BigDecimal amount, int attemptCount) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("isolation_txn_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(amount)
                .currency("INR")
                .status(status)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .attemptCount(attemptCount)
                .build());
    }

    private void addFailedRetryAttempt(Transaction transaction, int attemptNumber) {
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(attemptNumber)
                .amount(transaction.getAmount())
                .executedAt(Instant.now())
                .build());
    }

    /** Always recommends RETRY_PAYMENT, regardless of context - isolates the test from the mock AI's own heuristics. */
    private static final AIRecoveryProvider ALWAYS_RETRIES = context -> new RecoveryRecommendation(
            context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.9"),
            "test always recommends retrying", InterventionType.RETRY, context.transaction().amount(),
            Urgency.MEDIUM, "test", null);

    private RecoveryAgentService agentThatAlwaysRecommendsRetry() {
        return new RecoveryAgentService(transactionRepository, recoveryAttemptRepository, revenueRiskRepository,
                auditLogRepository, recoveryPolicyService, recoveryPolicyProperties, ALWAYS_RETRIES);
    }

    /** Counts how many times execute() was actually invoked, delegating to the real Spring-wired gateway (MockPaymentGateway by default). */
    private static class CountingPaymentGateway implements PaymentGateway {
        private final PaymentGateway delegate;
        private final AtomicInteger invocationCount = new AtomicInteger(0);

        CountingPaymentGateway(PaymentGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public PaymentExecutionResult execute(PaymentExecutionRequest request) {
            invocationCount.incrementAndGet();
            return delegate.execute(request);
        }
    }

    /** Plays the role of a (future Phase 7) caller: only invokes the gateway when policy says ALLOW. */
    private PaymentExecutionResult executeOnlyIfAllowed(RecoveryAgentEvaluationResponse response, BigDecimal transactionAmount,
                                                          CountingPaymentGateway gateway) {
        if (response.policyDecision().decision() != PolicyDecision.ALLOW) {
            return null;
        }
        String idempotencyKey = IdempotencyKeys.forAttempt(response.transactionId(), response.finalAction(), 1);
        PaymentExecutionRequest request = new PaymentExecutionRequest(
                response.transactionId(), response.externalTransactionId(), response.finalAction(),
                transactionAmount, "INR", idempotencyKey);
        return gateway.execute(request);
    }

    @Test
    void aiRecommendsRetry_policyStops_noPaymentGatewayExecutionOccurs() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), 3);
        addFailedRetryAttempt(txn, 1);
        addFailedRetryAttempt(txn, 2);

        RecoveryAgentEvaluationResponse response = agentThatAlwaysRecommendsRetry().evaluate(txn.getId());
        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.STOP);

        CountingPaymentGateway counting = new CountingPaymentGateway(realPaymentGateway);
        PaymentExecutionResult result = executeOnlyIfAllowed(response, txn.getAmount(), counting);

        assertThat(counting.invocationCount.get()).isZero();
        assertThat(result).isNull();
    }

    @Test
    void aiRecommendsRetry_policyEscalates_noPaymentGatewayExecutionOccurs() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"), 1);

        RecoveryAgentEvaluationResponse response = agentThatAlwaysRecommendsRetry().evaluate(txn.getId());
        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ESCALATE);

        CountingPaymentGateway counting = new CountingPaymentGateway(realPaymentGateway);
        PaymentExecutionResult result = executeOnlyIfAllowed(response, txn.getAmount(), counting);

        assertThat(counting.invocationCount.get()).isZero();
        assertThat(result).isNull();
    }

    @Test
    void aiRecommendsRetry_policyAllows_paymentGatewayCanExecuteInIsolation() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), 1);

        RecoveryAgentEvaluationResponse response = agentThatAlwaysRecommendsRetry().evaluate(txn.getId());
        assertThat(response.aiRecommendation().action()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(response.policyDecision().decision()).isEqualTo(PolicyDecision.ALLOW);

        CountingPaymentGateway counting = new CountingPaymentGateway(realPaymentGateway);
        PaymentExecutionResult result = executeOnlyIfAllowed(response, txn.getAmount(), counting);

        assertThat(counting.invocationCount.get()).isEqualTo(1);
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.simulated()).isTrue();
        assertThat(result.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
