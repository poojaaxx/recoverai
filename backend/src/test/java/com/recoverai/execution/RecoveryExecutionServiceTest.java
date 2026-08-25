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
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.domain.Urgency;
import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.payment.MockPaymentGateway;
import com.recoverai.payment.PaymentExecutionRequest;
import com.recoverai.payment.PaymentExecutionResult;
import com.recoverai.payment.PaymentFailureReason;
import com.recoverai.payment.PaymentGateway;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full Recovery Execution Pipeline coverage: policy boundary, {@code
 * RecoveryAttempt} lifecycle, attempt numbering, idempotency, provider
 * result handling, transaction-state transitions, {@code
 * amountRecovered} honesty, and the execution audit trail. Most tests use
 * the real {@link MockPaymentGateway} (via a manually-wired {@code
 * RecoveryExecutionService}, matching the established pattern from Phase
 * 5/6 tests); a few construct a stub {@link PaymentGateway} where a
 * specific provider outcome must be forced deterministically.
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryExecutionServiceTest {

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

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Execution Test Merchant")
                .email("exec-" + UUID.randomUUID() + "@example.com")
                .build());
    }

    // ---------------------------------------------------------------- test data + wiring helpers

    private Customer customer(int successCount, int failedCount) {
        return customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Customer " + UUID.randomUUID())
                .email("cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(successCount)
                .failedPaymentCount(failedCount)
                .build());
    }

    private Transaction transaction(Customer customer, TransactionStatus status, BigDecimal amount, String externalId) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId(externalId == null ? "exec_txn_" + UUID.randomUUID() : externalId)
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

    private static final AIRecoveryProvider ALWAYS_RETRIES = context -> new RecoveryRecommendation(
            context.transaction().transactionId(), RecoveryAction.RETRY_PAYMENT, new BigDecimal("0.9"),
            "test always recommends retrying", InterventionType.RETRY, context.transaction().amount(),
            Urgency.MEDIUM, "test", null);

    private RecoveryAgentService agentService(AIRecoveryProvider aiProvider) {
        return new RecoveryAgentService(transactionRepository, recoveryAttemptRepository, revenueRiskRepository,
                auditLogRepository, recoveryPolicyService, recoveryPolicyProperties, aiProvider);
    }

    private RecoveryExecutionService executionService(AIRecoveryProvider aiProvider, PaymentGateway gateway) {
        return new RecoveryExecutionService(transactionRepository, recoveryAttemptRepository, auditLogRepository,
                agentService(aiProvider), gateway, transactionManager);
    }

    private RecoveryExecutionService executionServiceAlwaysRetryingMock() {
        return executionService(ALWAYS_RETRIES, new MockPaymentGateway());
    }

    /** Counts invocations, delegating to a real MockPaymentGateway. */
    private static class CountingGateway implements PaymentGateway {
        final AtomicInteger count = new AtomicInteger(0);
        final PaymentGateway delegate = new MockPaymentGateway();
        List<PaymentExecutionRequest> requests = new java.util.ArrayList<>();

        @Override
        public PaymentExecutionResult execute(PaymentExecutionRequest request) {
            count.incrementAndGet();
            requests.add(request);
            return delegate.execute(request);
        }
    }

    // ---------------------------------------------------------------- 1-4. policy boundary

    @Test
    void policyAllow_callsGatewayExactlyOnce() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isEqualTo(1);
        assertThat(response.executed()).isTrue();
        assertThat(response.policyDecision().decision().name()).isEqualTo("ALLOW");
    }

    @Test
    void policyBlock_callsGatewayZeroTimes() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.RECOVERED, new BigDecimal("1899.00"), null);
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.executed()).isFalse();
        assertThat(response.policyDecision().decision().name()).isEqualTo("BLOCK");
    }

    @Test
    void policyEscalate_callsGatewayZeroTimes() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"), null);
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.executed()).isFalse();
        assertThat(response.requiresHumanApproval()).isTrue();
        assertThat(response.policyDecision().decision().name()).isEqualTo("ESCALATE");
    }

    @Test
    void policyStop_callsGatewayZeroTimes() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.STOPPED, new BigDecimal("7499.00"), null);
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.executed()).isFalse();
        assertThat(response.policyDecision().decision().name()).isEqualTo("STOP");
    }

    // ---------------------------------------------------------------- P0.1: lifecycle status persistence

    @Test
    void policyEscalate_persistsTransactionAsEscalated_viaLivePipeline_zeroGatewayCalls() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"), null);
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.policyDecision().decision().name()).isEqualTo("ESCALATE");
        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.ESCALATED);
    }

    @Test
    void policyStop_persistsTransactionAsStopped_viaLivePipeline_zeroGatewayCalls() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(1).amount(txn.getAmount()).executedAt(Instant.now()).build());
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(2).amount(txn.getAmount()).executedAt(Instant.now()).build());
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.policyDecision().decision().name()).isEqualTo("STOP");
        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.STOPPED);
    }

    @Test
    void policyEscalate_reEvaluatingAnAlreadyEscalatedTransaction_isIdempotent() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"), null);
        RecoveryExecutionService service = executionService(ALWAYS_RETRIES, new CountingGateway());

        service.execute(txn.getId());
        Instant firstUpdatedAt = transactionRepository.findById(txn.getId()).orElseThrow().getUpdatedAt();
        RecoveryExecutionResponse second = service.execute(txn.getId());

        assertThat(second.policyDecision().decision().name()).isEqualTo("ESCALATE");
        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.ESCALATED);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(firstUpdatedAt);
    }

    @Test
    void policyBlock_neverChangesTransactionStatus() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.RECOVERED, new BigDecimal("1899.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.RECOVERED);
    }

    @Test
    void allow_executingSuccessfully_neverMarksTransactionRecovered() {
        // P0.1's own scope check: confirming ALLOW's lifecycle behavior is unchanged by this phase -
        // execution success alone must still never set RECOVERED (only PaymentConfirmationService can).
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    // ---------------------------------------------------------------- 5-8. RecoveryAttempt lifecycle

    @Test
    void allow_createsExactlyOneRecoveryAttempt() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);
    }

    @Test
    void block_createsNoRecoveryAttempt() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.SUCCESS, new BigDecimal("999.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).isEmpty();
    }

    @Test
    void escalate_createsNoRecoveryAttempt() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).isEmpty();
    }

    @Test
    void stop_createsNoRecoveryAttempt() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.STOPPED, new BigDecimal("7499.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).isEmpty();
    }

    @Test
    void sendRecoveryReminder_isRecordedAsAnAuditableAttempt_neverCallsGateway_neverMarksRecovered() {
        // PENDING -> mock recommends SEND_RECOVERY_REMINDER, which policy ALLOWs. P0.2: this is
        // now a real, auditable, non-payment RecoveryAttempt - not a silent no-op.
        Customer c = customer(3, 1);
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId("exec_pending_" + UUID.randomUUID())
                .merchant(merchant).customer(c).amount(new BigDecimal("1500.00")).currency("INR")
                .status(TransactionStatus.PENDING).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.SEND_RECOVERY_REMINDER, new BigDecimal("0.6"),
                "reminder", InterventionType.REENGAGE, BigDecimal.TEN, Urgency.LOW, "test", null), gateway)
                .execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.executed()).isFalse();
        assertThat(response.provider()).isNull();
        assertThat(response.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.executionNote()).contains("no payment gateway was called");

        List<RecoveryAttempt> attempts = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getAction()).isEqualTo(RecoveryAction.SEND_RECOVERY_REMINDER);
        assertThat(attempts.get(0).getStatus()).isEqualTo(RecoveryAttemptStatus.SUCCESS);
        assertThat(attempts.get(0).getProvider()).isNull();
        assertThat(attempts.get(0).getAmountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);

        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void sendRecoveryReminder_respectsDuplicateActionProtection_secondCallIsBlocked() {
        // A recorded reminder now counts as a real RecoveryAttempt, so the existing
        // DUPLICATE_ACTION policy check (24h window) applies to it exactly like a payment action.
        Customer c = customer(3, 1);
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId("exec_pending_" + UUID.randomUUID())
                .merchant(merchant).customer(c).amount(new BigDecimal("1500.00")).currency("INR")
                .status(TransactionStatus.PENDING).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        RecoveryExecutionService service = executionService(context -> new RecoveryRecommendation(
                context.transaction().transactionId(), RecoveryAction.SEND_RECOVERY_REMINDER, new BigDecimal("0.6"),
                "reminder", InterventionType.REENGAGE, BigDecimal.TEN, Urgency.LOW, "test", null), new MockPaymentGateway());

        RecoveryExecutionResponse first = service.execute(txn.getId());
        RecoveryExecutionResponse second = service.execute(txn.getId());

        assertThat(first.policyDecision().decision().name()).isEqualTo("ALLOW");
        assertThat(second.policyDecision().decision().name()).isEqualTo("BLOCK");
        assertThat(second.executed()).isFalse();
        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);
    }

    // ---------------------------------------------------------------- 9-10. attempt numbering / idempotency

    @Test
    void attemptNumber_isDerivedFromPersistedHistory_notInMemory() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.SEND_RECOVERY_REMINDER).status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(1).amount(txn.getAmount()).executedAt(Instant.now()).build());

        RecoveryExecutionResponse response = executionServiceAlwaysRetryingMock().execute(txn.getId());

        RecoveryAttempt created = recoveryAttemptRepository.findById(response.recoveryAttemptId()).orElseThrow();
        assertThat(created.getAttemptNumber()).isEqualTo(2);
    }

    @Test
    void idempotencyKey_isDeterministic_matchesIdempotencyKeysUtility() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);

        RecoveryExecutionResponse response = executionServiceAlwaysRetryingMock().execute(txn.getId());

        RecoveryAttempt created = recoveryAttemptRepository.findById(response.recoveryAttemptId()).orElseThrow();
        String expectedKey = com.recoverai.payment.IdempotencyKeys.forAttempt(txn.getId(), RecoveryAction.RETRY_PAYMENT, 1);
        assertThat(created.getIdempotencyKey()).isEqualTo(expectedKey);
    }

    // ---------------------------------------------------------------- 11-12. repeated execution (sequential)
    //
    // A sequential replay is NOT the same case as a concurrent race (see
    // RecoveryExecutionConcurrencyTest, which covers the literal
    // "same idempotency key" scenario): once the first call's attempt has
    // committed, a second call legitimately computes the NEXT attempt
    // number (Phase 7 never treats "some time later" as forbidden - that
    // is Phase 4's RETRY_LIMIT's job, not this layer's). What DOES stop a
    // rapid repeat here is Phase 4's own pre-existing DUPLICATE_ACTION
    // policy check: RecoveryPolicyService.evaluate() is re-run fresh on
    // every execute() call, sees the first attempt's SUCCESS within the
    // duplicate-action window, and returns BLOCK - so the gateway is
    // still never called a second time, but via the policy boundary
    // remaining authoritative, not via execution-layer idempotency-key
    // matching. This is the architecturally correct outcome: Phase 7
    // does not need (or want) its own separate duplicate-prevention rule
    // that could drift from Phase 4's.

    @Test
    void sameActionRepeatedShortlyAfterSuccess_isBlockedByPhase4DuplicateActionCheck_noSecondGatewayCall() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        CountingGateway gateway = new CountingGateway();
        RecoveryExecutionService service = executionService(ALWAYS_RETRIES, gateway);

        RecoveryExecutionResponse first = service.execute(txn.getId());
        RecoveryExecutionResponse second = service.execute(txn.getId());

        assertThat(first.executed()).isTrue();
        assertThat(gateway.count.get()).isEqualTo(1);
        assertThat(second.executed()).isFalse();
        assertThat(second.policyDecision().decision().name()).isEqualTo("BLOCK");
        assertThat(second.recoveryAttemptId()).isNull();
        // Only the one real execution attempt exists - the blocked second call never reserved another.
        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);
    }

    @Test
    void preExistingSuccessfulAttempt_preventsANewGatewayCall_regardlessOfHowItWasCreated() {
        // A completed SUCCESS attempt for this action already exists (here, seeded directly
        // rather than produced by a prior execute() call). RecoveryPolicyService's own
        // DUPLICATE_ACTION check catches this on the fresh policy re-check - the same safety
        // net proven above, confirmed here against a pre-existing row this service never wrote
        // itself. The genuine "identical idempotency key" race path (two requests computing the
        // very same attempt number concurrently) is covered separately in
        // RecoveryExecutionConcurrencyTest.
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        String key = com.recoverai.payment.IdempotencyKeys.forAttempt(txn.getId(), RecoveryAction.RETRY_PAYMENT, 1);
        recoveryAttemptRepository.saveAndFlush(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).idempotencyKey(key).amount(txn.getAmount())
                .provider("mock").providerReference("mock_" + key).amountRecovered(BigDecimal.ZERO)
                .executedAt(Instant.now()).build());
        CountingGateway gateway = new CountingGateway();

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, gateway).execute(txn.getId());

        assertThat(gateway.count.get()).isZero();
        assertThat(response.executed()).isFalse();
        assertThat(response.policyDecision().decision().name()).isEqualTo("BLOCK");
        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);
    }

    // ---------------------------------------------------------------- 14-21. provider result handling

    @Test
    void mockSuccess_recordsSuccessAttempt_amountRecoveredZero() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), "exec_ok_txn");

        RecoveryExecutionResponse response = executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(response.executionStatus()).isEqualTo(RecoveryAttemptStatus.SUCCESS);
        assertThat(response.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.simulated()).isTrue();
    }

    @Test
    void mockDecline_recordsFailedAttempt_transactionNotRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), "mock-decline-test");

        RecoveryExecutionResponse response = executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(response.executionStatus()).isEqualTo(RecoveryAttemptStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureReason.DECLINED);
        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void mockTimeout_recordsFailedAttempt_notRetriedAutomatically() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), "mock-timeout-test");

        RecoveryExecutionResponse response = executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(response.executionStatus()).isEqualTo(RecoveryAttemptStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureReason.TIMEOUT);
        // Exactly one attempt exists - Phase 7 never auto-retries a failed attempt.
        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(txn.getId())).hasSize(1);
    }

    @Test
    void providerUnavailable_failsClosed_notRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        PaymentGateway unavailable = req -> new PaymentExecutionResult(false, "razorpay", null, req.transactionId(),
                req.action(), req.amount(), req.currency(), BigDecimal.ZERO, false, "failed",
                PaymentFailureReason.PROVIDER_UNAVAILABLE, "Provider unavailable", req.idempotencyKey(), Instant.now());

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, unavailable).execute(txn.getId());

        assertThat(response.executionStatus()).isEqualTo(RecoveryAttemptStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void malformedResponse_failsClosed_notRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        PaymentGateway malformed = req -> new PaymentExecutionResult(false, "razorpay", null, req.transactionId(),
                req.action(), req.amount(), req.currency(), BigDecimal.ZERO, false, "failed",
                PaymentFailureReason.MALFORMED_RESPONSE, "Response did not parse", req.idempotencyKey(), Instant.now());

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, malformed).execute(txn.getId());

        assertThat(response.executionStatus()).isEqualTo(RecoveryAttemptStatus.FAILED);
        assertThat(response.failureCode()).isEqualTo(PaymentFailureReason.MALFORMED_RESPONSE);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void amountMismatch_failsClosed_notRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        PaymentGateway mismatch = req -> new PaymentExecutionResult(false, "razorpay", null, req.transactionId(),
                req.action(), req.amount(), req.currency(), BigDecimal.ZERO, false, "failed",
                PaymentFailureReason.AMOUNT_MISMATCH, "Amount did not match", req.idempotencyKey(), Instant.now());

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, mismatch).execute(txn.getId());

        assertThat(response.failureCode()).isEqualTo(PaymentFailureReason.AMOUNT_MISMATCH);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void transactionMismatch_failsClosed_notRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        PaymentGateway mismatch = req -> new PaymentExecutionResult(false, "razorpay", null, req.transactionId(),
                req.action(), req.amount(), req.currency(), BigDecimal.ZERO, false, "failed",
                PaymentFailureReason.TRANSACTION_MISMATCH, "Transaction identity did not match", req.idempotencyKey(), Instant.now());

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, mismatch).execute(txn.getId());

        assertThat(response.failureCode()).isEqualTo(PaymentFailureReason.TRANSACTION_MISMATCH);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    // ---------------------------------------------------------------- 22-25. transaction state transitions

    @Test
    void paymentLinkCreatedSuccessfully_transactionRemainsUnresolved_notRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), "exec_link_ok");

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void confirmedPayment_withNonZeroAmountRecovered_transitionsTransactionToRecovered() {
        // No real gateway can produce this today (Phase 6) - this proves the mapping itself is
        // implemented correctly for when a future provider confirmation mechanism can.
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        PaymentGateway confirmedPayment = req -> new PaymentExecutionResult(true, "razorpay", "pay_confirmed_123",
                req.transactionId(), req.action(), req.amount(), req.currency(), req.amount(), false, "paid",
                null, null, req.idempotencyKey(), Instant.now());

        executionService(ALWAYS_RETRIES, confirmedPayment).execute(txn.getId());

        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.RECOVERED);
    }

    @Test
    void providerFailure_transactionNeverMarkedRecovered() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), "mock-decline-fail");

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    // ---------------------------------------------------------------- 26-29. money

    @Test
    void exactTransactionAmount_isPreservedThroughExecution() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("7499.37"), null);

        RecoveryExecutionResponse response = executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(response.amount()).isEqualByComparingTo("7499.37");
        RecoveryAttempt attempt = recoveryAttemptRepository.findById(response.recoveryAttemptId()).orElseThrow();
        assertThat(attempt.getAmount()).isEqualByComparingTo("7499.37");
    }

    @Test
    void confirmedRecoveryAmount_isCorrectlyPersisted() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("5000.00"), null);
        PaymentGateway confirmedPayment = req -> new PaymentExecutionResult(true, "razorpay", "pay_conf", req.transactionId(),
                req.action(), req.amount(), req.currency(), req.amount(), false, "paid", null, null, req.idempotencyKey(), Instant.now());

        RecoveryExecutionResponse response = executionService(ALWAYS_RETRIES, confirmedPayment).execute(txn.getId());

        assertThat(response.amountRecovered()).isEqualByComparingTo("5000.00");
        RecoveryAttempt attempt = recoveryAttemptRepository.findById(response.recoveryAttemptId()).orElseThrow();
        assertThat(attempt.getAmountRecovered()).isEqualByComparingTo("5000.00");
    }

    // ---------------------------------------------------------------- 30-36. audit trail

    @Test
    void successfulExecution_writesStartedAndCompletedAuditEvents() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        List<String> eventTypes = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .map(a -> a.getEventType()).toList();
        assertThat(eventTypes).contains("RECOVERY_EXECUTION_STARTED", "RECOVERY_EXECUTION_COMPLETED");
    }

    @Test
    void failedExecution_writesFailedAuditEvent() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), "mock-decline-audit");

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        List<String> eventTypes = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .map(a -> a.getEventType()).toList();
        assertThat(eventTypes).contains("RECOVERY_EXECUTION_STARTED", "RECOVERY_EXECUTION_FAILED");
    }

    @Test
    void blockedDecision_writesBlockedAuditEvent() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.RECOVERED, new BigDecimal("1899.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .anyMatch(a -> "RECOVERY_EXECUTION_BLOCKED".equals(a.getEventType()))).isTrue();
    }

    @Test
    void escalatedDecision_writesEscalatedAuditEvent() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .anyMatch(a -> "RECOVERY_EXECUTION_ESCALATED".equals(a.getEventType()))).isTrue();
    }

    @Test
    void stoppedDecision_writesStoppedAuditEvent() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.STOPPED, new BigDecimal("7499.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        assertThat(auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .anyMatch(a -> "RECOVERY_EXECUTION_STOPPED".equals(a.getEventType()))).isTrue();
    }

    @Test
    void repeatedCallAfterSuccess_addsExactlyOneBlockedAuditEvent_noReExecutionAuditNoise() {
        // The second call is a real, distinct, auditable event (an attempted duplicate action
        // that Phase 4 correctly blocked) - it should be recorded, just not as a second
        // STARTED/COMPLETED pair implying the provider was called again.
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);
        RecoveryExecutionService service = executionServiceAlwaysRetryingMock();

        service.execute(txn.getId());
        List<String> eventsAfterFirst = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .filter(a -> a.getEventType().startsWith("RECOVERY_EXECUTION_")).map(a -> a.getEventType()).toList();
        assertThat(eventsAfterFirst).containsExactly("RECOVERY_EXECUTION_STARTED", "RECOVERY_EXECUTION_COMPLETED");

        service.execute(txn.getId());
        List<String> eventsAfterSecond = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).stream()
                .filter(a -> a.getEventType().startsWith("RECOVERY_EXECUTION_")).map(a -> a.getEventType()).toList();
        assertThat(eventsAfterSecond).containsExactly(
                "RECOVERY_EXECUTION_STARTED", "RECOVERY_EXECUTION_COMPLETED", "RECOVERY_EXECUTION_BLOCKED");
    }

    // ---------------------------------------------------------------- security

    @Test
    void auditMetadata_neverContainsSecretLikeFields() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"), null);

        executionServiceAlwaysRetryingMock().execute(txn.getId());

        auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId()).forEach(audit ->
                assertThat(audit.getMetadata()).doesNotContainKeys("apiKey", "keySecret", "authorization", "Authorization"));
    }

    // ---------------------------------------------------------------- misc

    @Test
    void unknownTransactionId_throwsNotFound() {
        assertThatThrownBy(() -> executionServiceAlwaysRetryingMock().execute(UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}
