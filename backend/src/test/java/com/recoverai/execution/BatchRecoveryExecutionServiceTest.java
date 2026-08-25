package com.recoverai.execution;

import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.config.RecoveryPolicyProperties;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.BatchExecutionOutcome;
import com.recoverai.dto.BatchExecutionResponse;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.webhook.PaymentConfirmationService;
import com.recoverai.webhook.RazorpayWebhookSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 14, section 2/5 - bounded batch recovery execution. Covers batch
 * size/aggregate limits, mixed decision outcomes, duplicate-id collapsing,
 * provider failure, and that the batch path reuses the exact same
 * confirmation pipeline as single-transaction execution (no parallel
 * "batch confirmed" shortcut exists).
 */
@SpringBootTest
@ActiveProfiles("test")
class BatchRecoveryExecutionServiceTest {

    @Autowired
    private BatchRecoveryExecutionService batchRecoveryExecutionService;
    @Autowired
    private RecoveryAgentService recoveryAgentService;
    @Autowired
    private RecoveryExecutionService recoveryExecutionService;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private PaymentConfirmationService paymentConfirmationService;

    private static final String WEBHOOK_SECRET = "test_webhook_secret";

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Batch Test Merchant")
                .email("batch-" + UUID.randomUUID() + "@example.com")
                .build());
    }

    // ---------------------------------------------------------------- helpers

    private Customer customer(int successCount, int failedCount) {
        return customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Batch Customer " + UUID.randomUUID())
                .email("batch-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(successCount)
                .failedPaymentCount(failedCount)
                .build());
    }

    private Transaction transaction(Customer customer, TransactionStatus status, BigDecimal amount, String externalId) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId(externalId == null ? "batch_txn_" + UUID.randomUUID() : externalId)
                .merchant(merchant)
                .customer(customer)
                .amount(amount)
                .currency("INR")
                .status(status)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .attemptCount(1)
                .build());
    }

    /** Easily-ALLOW-eligible transaction: strong customer history, fresh, low amount. */
    private Transaction easyAllowTransaction() {
        return transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("500.00"), null);
    }

    private BatchRecoveryExecutionService serviceWithLimits(int maxCount, BigDecimal maxAggregate) {
        RecoveryPolicyProperties properties = new RecoveryPolicyProperties();
        properties.setMaxBatchTransactionCount(maxCount);
        properties.setMaxBatchAggregateAmount(maxAggregate);
        return new BatchRecoveryExecutionService(transactionRepository, recoveryAgentService,
                recoveryExecutionService, properties, auditLogRepository);
    }

    // ---------------------------------------------------------------- limits

    @Test
    void batchSizeLimit_exceeded_rejectsWholeRequest() {
        List<UUID> ids = List.of(easyAllowTransaction().getId(), easyAllowTransaction().getId(),
                easyAllowTransaction().getId());

        assertThatThrownBy(() -> serviceWithLimits(2, new BigDecimal("100000")).executeBatch(ids, "test-admin"))
                .isInstanceOf(BatchSizeExceededException.class);
    }

    @Test
    void emptyBatchRequest_isRejected() {
        assertThatThrownBy(() -> batchRecoveryExecutionService.executeBatch(List.of(), "test-admin"))
                .isInstanceOf(EmptyBatchRequestException.class);
    }

    @Test
    void aggregateAmountLimit_secondTransactionSkipped_firstStillExecutes() {
        Transaction first = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("800.00"), null);
        Transaction second = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("800.00"), null);

        BatchExecutionResponse response = serviceWithLimits(10, new BigDecimal("1000.00"))
                .executeBatch(List.of(first.getId(), second.getId()), "test-admin");

        assertThat(response.executedCount()).isEqualTo(1);
        assertThat(response.skippedPortfolioLimitCount()).isEqualTo(1);
        assertThat(response.aggregateAmountExecuted()).isEqualByComparingTo("800.00");
        assertThat(response.results().stream().filter(r -> r.outcome() == BatchExecutionOutcome.SKIPPED_PORTFOLIO_LIMIT))
                .hasSize(1);
        // The skip is audited with a clear, specific reason (not silently dropped). Processing
        // order follows the request list, so the second transaction is deterministically the one skipped.
        assertThat(auditLogRepository.findByTransactionIdOrderByTimestampAsc(second.getId()))
                .anyMatch(a -> "RECOVERY_BATCH_SKIPPED_PORTFOLIO_LIMIT".equals(a.getEventType()));
    }

    @Test
    void aggregateAmountLimit_neverPartiallyExceeded() {
        Transaction first = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("900.00"), null);
        Transaction second = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("900.00"), null);

        BatchExecutionResponse response = serviceWithLimits(10, new BigDecimal("1000.00"))
                .executeBatch(List.of(first.getId(), second.getId()), "test-admin");

        assertThat(response.aggregateAmountExecuted()).isLessThanOrEqualTo(new BigDecimal("1000.00"));
    }

    // ---------------------------------------------------------------- authorization / trust boundary

    @Test
    void neverTrustsClientSuppliedAmountOrAction_onlyReloadsFromDatabase() {
        // The request is only a list of ids - BatchExecutionRequest has no amount/action/authorization
        // field at all, so this is a structural guarantee, exercised here via the service directly.
        Transaction txn = easyAllowTransaction();
        BigDecimal originalAmount = txn.getAmount();

        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(List.of(txn.getId()), "test-admin");

        assertThat(response.results().get(0).amount()).isEqualByComparingTo(originalAmount);
    }

    // ---------------------------------------------------------------- mixed outcomes

    @Test
    void mixedBatch_allowBlockEscalateStop_eachClassifiedCorrectly() {
        Transaction allow = easyAllowTransaction();
        Transaction block = transaction(customer(5, 1), TransactionStatus.SUCCESS, new BigDecimal("500.00"), null);
        Transaction escalate = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("47500.00"), null);
        // Strong history (high recovery probability) so the AI does not itself recommend
        // ESCALATE on low probability - it recommends CREATE_PAYMENT_LINK once 3 prior
        // attempts of any type exist (RETRY_ATTEMPT_THRESHOLD already excludes another
        // retry recommendation), and REPEATED_FAILURE (unconditional on action) then stops it.
        Transaction stop = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("500.00"), null);
        for (int i = 1; i <= 3; i++) {
            recoveryAttemptRepository.save(RecoveryAttempt.builder()
                    .transaction(stop).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.FAILED)
                    .attemptNumber(i).amount(stop.getAmount()).executedAt(Instant.now()).build());
        }

        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(
                List.of(allow.getId(), block.getId(), escalate.getId(), stop.getId()), "test-admin");

        assertThat(response.executedCount()).isEqualTo(1);
        assertThat(response.blockedCount()).isEqualTo(1);
        assertThat(response.escalatedCount()).isEqualTo(1);
        assertThat(response.stoppedCount()).isEqualTo(1);

        // ESCALATE/STOP must durably persist their lifecycle status - never execute.
        assertThat(transactionRepository.findById(escalate.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.ESCALATED);
        assertThat(transactionRepository.findById(stop.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.STOPPED);
    }

    @Test
    void duplicateTransactionIdsInRequest_collapsedToOneExecution() {
        Transaction txn = easyAllowTransaction();

        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(
                List.of(txn.getId(), txn.getId(), txn.getId()), "test-admin");

        assertThat(response.totalRequested()).isEqualTo(3);
        assertThat(response.distinctCount()).isEqualTo(1);
        assertThat(response.duplicateRequestCount()).isEqualTo(2);
        assertThat(response.executedCount()).isEqualTo(1);
        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);
    }

    @Test
    void unknownTransactionId_reportedAsNotFound_doesNotAbortOtherItems() {
        Transaction real = easyAllowTransaction();
        UUID fake = UUID.randomUUID();

        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(
                List.of(fake, real.getId()), "test-admin");

        assertThat(response.notFoundCount()).isEqualTo(1);
        assertThat(response.executedCount()).isEqualTo(1);
    }

    @Test
    void providerFailure_reportedAsFailedProviderCall_notExecuted() {
        Transaction txn = transaction(customer(10, 0), TransactionStatus.FAILED, new BigDecimal("500.00"),
                "mock-decline-" + UUID.randomUUID());

        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(List.of(txn.getId()), "test-admin");

        assertThat(response.failedProviderCallCount()).isEqualTo(1);
        assertThat(response.executedCount()).isEqualTo(0);
        assertThat(response.aggregateAmountExecuted()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------- confirmed revenue discipline

    @Test
    void executedInBatch_noConfirmedRevenueBeforeWebhook_confirmedAfterValidWebhook() throws Exception {
        Transaction txn = easyAllowTransaction();

        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(List.of(txn.getId()), "test-admin");
        assertThat(response.executedCount()).isEqualTo(1);
        var item = response.results().get(0);
        assertThat(recoveryAttemptRepository.findById(item.recoveryAttemptId()).orElseThrow().getPaymentConfirmationStatus().name())
                .isEqualTo("NOT_CONFIRMED");

        RecoveryAttempt attempt = recoveryAttemptRepository.findById(item.recoveryAttemptId()).orElseThrow();
        String payload = """
                {"event":"payment_link.paid","payload":{\
                "payment_link":{"entity":{"id":"%s"}},\
                "payment":{"entity":{"id":"pay_batch_test","amount":%d,"currency":"INR"}}}}\
                """.formatted(attempt.getProviderReference(), attempt.getAmount().movePointRight(2).longValueExact());
        String signature = RazorpayWebhookSignature.sign(payload, WEBHOOK_SECRET);
        paymentConfirmationService.processRazorpayWebhook(payload, signature, "evt_batch_" + UUID.randomUUID());

        RecoveryAttempt confirmed = recoveryAttemptRepository.findById(item.recoveryAttemptId()).orElseThrow();
        assertThat(confirmed.getPaymentConfirmationStatus().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void invalidWebhookSignature_cannotConfirmBatchExecutedAttempt() {
        Transaction txn = easyAllowTransaction();
        BatchExecutionResponse response = batchRecoveryExecutionService.executeBatch(List.of(txn.getId()), "test-admin");
        var item = response.results().get(0);
        RecoveryAttempt attempt = recoveryAttemptRepository.findById(item.recoveryAttemptId()).orElseThrow();

        String payload = """
                {"event":"payment_link.paid","payload":{\
                "payment_link":{"entity":{"id":"%s"}},\
                "payment":{"entity":{"id":"pay_batch_bad","amount":%d,"currency":"INR"}}}}\
                """.formatted(attempt.getProviderReference(), attempt.getAmount().movePointRight(2).longValueExact());
        paymentConfirmationService.processRazorpayWebhook(payload, "not-a-valid-signature", "evt_batch_bad_" + UUID.randomUUID());

        RecoveryAttempt stillUnconfirmed = recoveryAttemptRepository.findById(item.recoveryAttemptId()).orElseThrow();
        assertThat(stillUnconfirmed.getPaymentConfirmationStatus().name()).isEqualTo("NOT_CONFIRMED");
    }
}
