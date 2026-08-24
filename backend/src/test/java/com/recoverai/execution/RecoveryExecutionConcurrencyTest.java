package com.recoverai.execution;

import com.recoverai.agent.AIRecoveryProvider;
import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.agent.RecoveryRecommendation;
import com.recoverai.config.RecoveryPolicyProperties;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.InterventionType;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.domain.Urgency;
import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.payment.MockPaymentGateway;
import com.recoverai.payment.PaymentExecutionRequest;
import com.recoverai.payment.PaymentExecutionResult;
import com.recoverai.payment.PaymentGateway;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves "at most one provider call for the same recovery attempt" holds
 * under genuine thread-level concurrency, not just sequential replay - see
 * {@code RecoveryExecutionService}'s javadoc for the database-constraint
 * mechanism this relies on.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryExecutionConcurrencyTest {

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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private static final AIRecoveryProvider ALWAYS_RETRIES = context -> new RecoveryRecommendation(
            context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.9"),
            "test always recommends retrying", InterventionType.RETRY, context.transaction().amount(),
            Urgency.MEDIUM, "test", null);

    private RecoveryExecutionService executionService(PaymentGateway gateway) {
        RecoveryAgentService agentService = new RecoveryAgentService(transactionRepository, recoveryAttemptRepository,
                revenueRiskRepository, auditLogRepository, recoveryPolicyService, recoveryPolicyProperties, ALWAYS_RETRIES);
        return new RecoveryExecutionService(transactionRepository, recoveryAttemptRepository, auditLogRepository,
                agentService, gateway, transactionManager);
    }

    private static class CountingGateway implements PaymentGateway {
        final AtomicInteger count = new AtomicInteger(0);
        final PaymentGateway delegate = new MockPaymentGateway();

        @Override
        public PaymentExecutionResult execute(PaymentExecutionRequest request) {
            count.incrementAndGet();
            // A small delay widens the race window so both threads are more likely to reach
            // the reservation step before either commits - making the test meaningfully exercise
            // the concurrent path rather than accidentally always serializing trivially.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.execute(request);
        }
    }

    @Test
    void twoConcurrentExecutionRequests_resultInAtMostOneProviderCall() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Concurrency Test Merchant")
                .email("concurrency-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Concurrency Customer").email("concurrency-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(10).failedPaymentCount(0).build());
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId("concurrency_txn_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("2499.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name()).attemptCount(1).build());

        CountingGateway gateway = new CountingGateway();
        RecoveryExecutionService service = executionService(gateway);

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<java.util.concurrent.Future<RecoveryExecutionResponse>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return service.execute(txn.getId());
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<RecoveryExecutionResponse> responses = new java.util.ArrayList<>();
        for (var future : futures) {
            responses.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(gateway.count.get()).isEqualTo(1);
        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);

        // Exactly one response reflects the actual execution; the other resolves as a duplicate.
        long duplicateCount = responses.stream().filter(RecoveryExecutionResponse::duplicate).count();
        assertThat(duplicateCount).isEqualTo(1);

        UUID sharedAttemptId = responses.get(0).recoveryAttemptId();
        assertThat(responses.get(1).recoveryAttemptId()).isEqualTo(sharedAttemptId);
    }
}
