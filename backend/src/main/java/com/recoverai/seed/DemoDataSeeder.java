package com.recoverai.seed;

import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a deterministic, synthetic demo dataset for one merchant.
 * <p>
 * This is NOT the Phase 3 risk-scoring engine and NOT the Phase 4 safety
 * policy engine. {@link RevenueRisk} and {@link RecoveryAttempt} rows
 * created here are hand-authored seed facts — "here is what already
 * happened to this transaction, for demo purposes" — not the output of a
 * live scoring algorithm or a policy decision. Every failure category,
 * amount, and outcome is application-invented synthetic data, not real
 * Razorpay data.
 * <p>
 * Determinism: a single {@link Random} seeded with {@link #RANDOM_SEED} is
 * consumed in a fixed order (merchant → customers → demo transactions →
 * bulk transactions), so the categorical shape of the dataset (status mix,
 * failure categories, customer history profiles, which transactions get
 * risk/recovery records) is reproducible across runs. {@code createdAt}
 * timestamps are anchored to "now" minus a deterministic offset, so they
 * shift with the run date but always land within the same rolling window —
 * appropriate for dashboard "last N days" views.
 */
@Service
@RequiredArgsConstructor
public class DemoDataSeeder {

    public static final long RANDOM_SEED = 42L;
    public static final int CUSTOMER_COUNT = 120;
    public static final int TOTAL_TRANSACTIONS = 500;
    public static final int DEMO_TRANSACTION_COUNT = 5;
    public static final int BULK_TRANSACTION_COUNT = TOTAL_TRANSACTIONS - DEMO_TRANSACTION_COUNT;
    private static final int DAYS_SPAN = 45;

    private final MerchantRepository merchantRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final RevenueRiskRepository revenueRiskRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final AuditLogRepository auditLogRepository;

    private enum HistoryProfile { STRONG, MIXED, WEAK }

    private static final FailureCategory[] RECOVERABLE_LEANING_FAILURES = {
            FailureCategory.TEMPORARY_FAILURE, FailureCategory.INSUFFICIENT_FUNDS, FailureCategory.NETWORK_ERROR
    };
    private static final FailureCategory[] HARD_FAILURES = {
            FailureCategory.BANK_DECLINED, FailureCategory.AUTHENTICATION_FAILURE,
            FailureCategory.LIMIT_EXCEEDED, FailureCategory.UNKNOWN
    };

    /**
     * Wipes any previously seeded data before regenerating, so {@code
     * seed()} is safe to call more than once (repeated test runs sharing
     * one database, or a future {@code POST /api/demo/reset} + reseed
     * workflow) without hitting unique-constraint violations on the fixed
     * merchant/demo-transaction identifiers.
     */
    @Transactional
    public SeedReport seed() {
        resetAll();

        Random rng = new Random(RANDOM_SEED);
        Instant now = Instant.now();

        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Nimbus Retail")
                .email("ops@nimbusretail.example")
                .build());

        List<Customer> customers = new ArrayList<>(CUSTOMER_COUNT);
        for (int i = 0; i < CUSTOMER_COUNT; i++) {
            customers.add(generateCustomer(merchant, rng, i));
        }
        customers = customerRepository.saveAll(customers);

        Map<String, String> demoTransactionIds = new LinkedHashMap<>();
        seedDemoTransactions(merchant, customers, rng, now, demoTransactionIds);
        seedBulkTransactions(merchant, customers, rng, now);

        return buildReport(demoTransactionIds);
    }

    private void resetAll() {
        auditLogRepository.deleteAllInBatch();
        recoveryAttemptRepository.deleteAllInBatch();
        revenueRiskRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        merchantRepository.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- customers

    private Customer generateCustomer(Merchant merchant, Random rng, int index) {
        HistoryProfile profile = pickHistoryProfile(rng);
        int successCount;
        int failCount;
        switch (profile) {
            case STRONG -> {
                successCount = 4 + rng.nextInt(12); // 4-15
                failCount = rng.nextInt(2);          // 0-1
            }
            case WEAK -> {
                successCount = rng.nextInt(2);       // 0-1
                failCount = 3 + rng.nextInt(6);      // 3-8
            }
            default -> {
                successCount = 2 + rng.nextInt(5);   // 2-6
                failCount = 1 + rng.nextInt(4);      // 1-4
            }
        }
        BigDecimal avgOrderValue = randomAmount(rng, 500, 6000);
        BigDecimal totalHistoricalValue = avgOrderValue
                .multiply(BigDecimal.valueOf(successCount))
                .setScale(2, RoundingMode.HALF_UP);

        // Phase 14: a deterministic minority of customers (index % 12 == 11,
        // ~8%) have opted out of recovery contact, purely from the index -
        // no extra rng draw, so the rest of the seeded dataset's shape is
        // unaffected - giving the demo a real, reproducible opt-out example
        // without relying on live user action.
        boolean recoveryContactAllowed = index % 12 != 11;

        return Customer.builder()
                .merchant(merchant)
                .name("Demo Customer " + (index + 1))
                .email("customer" + (index + 1) + "@nimbusretail-demo.example")
                .phone("+9198765" + String.format("%05d", index))
                .successfulPaymentCount(successCount)
                .failedPaymentCount(failCount)
                .totalHistoricalValue(totalHistoricalValue)
                .recoveryContactAllowed(recoveryContactAllowed)
                .build();
    }

    private HistoryProfile pickHistoryProfile(Random rng) {
        double roll = rng.nextDouble();
        if (roll < 0.50) return HistoryProfile.STRONG;
        if (roll < 0.80) return HistoryProfile.MIXED;
        return HistoryProfile.WEAK;
    }

    // ---------------------------------------------------------------- bulk transactions

    private void seedBulkTransactions(Merchant merchant, List<Customer> customers, Random rng, Instant now) {
        // Target distribution across BULK_TRANSACTION_COUNT (495). Percentages
        // are illustrative synthetic-dataset targets, not derived from any
        // real merchant's actual outcome mix.
        int successCount = (int) Math.round(BULK_TRANSACTION_COUNT * 0.55);
        int failedCount = (int) Math.round(BULK_TRANSACTION_COUNT * 0.20);
        int pendingCount = (int) Math.round(BULK_TRANSACTION_COUNT * 0.05);
        int abandonedCount = (int) Math.round(BULK_TRANSACTION_COUNT * 0.08);
        int recoveredCount = (int) Math.round(BULK_TRANSACTION_COUNT * 0.06);
        int escalatedCount = (int) Math.round(BULK_TRANSACTION_COUNT * 0.03);
        int stoppedCount = BULK_TRANSACTION_COUNT
                - successCount - failedCount - pendingCount - abandonedCount - recoveredCount - escalatedCount;

        List<Transaction> batch = new ArrayList<>(BULK_TRANSACTION_COUNT);
        for (int i = 0; i < successCount; i++) {
            batch.add(buildBaseTransaction(merchant, customers, rng, now, TransactionStatus.SUCCESS));
        }
        for (int i = 0; i < pendingCount; i++) {
            batch.add(buildBaseTransaction(merchant, customers, rng, now, TransactionStatus.PENDING));
        }
        for (int i = 0; i < abandonedCount; i++) {
            batch.add(buildAbandonedTransaction(merchant, customers, rng, now));
        }
        for (int i = 0; i < failedCount; i++) {
            batch.add(buildFailedTransaction(merchant, customers, rng, now));
        }

        // Persist the simple cases first, then handle the ones that need
        // child rows (RevenueRisk / RecoveryAttempt / AuditLog) individually.
        transactionRepository.saveAll(batch);

        for (int i = 0; i < recoveredCount; i++) {
            seedRecoveredTransaction(merchant, customers, rng, now);
        }
        for (int i = 0; i < escalatedCount; i++) {
            seedEscalatedTransaction(merchant, customers, rng, now);
        }
        for (int i = 0; i < stoppedCount; i++) {
            seedStoppedTransaction(merchant, customers, rng, now);
        }
    }

    private Transaction buildBaseTransaction(Merchant merchant, List<Customer> customers, Random rng,
                                              Instant now, TransactionStatus status) {
        Customer customer = randomCustomer(customers, rng);
        return Transaction.builder()
                .externalTransactionId(newExternalId(rng))
                .merchant(merchant)
                .customer(customer)
                .amount(randomBucketedAmount(rng))
                .currency("INR")
                .status(status)
                .paymentMethod(randomPaymentMethod(rng))
                .attemptCount(1)
                .createdAt(randomPastInstant(now, rng))
                .updatedAt(now)
                .build();
    }

    private Transaction buildAbandonedTransaction(Merchant merchant, List<Customer> customers, Random rng, Instant now) {
        Customer customer = randomCustomer(customers, rng);
        return Transaction.builder()
                .externalTransactionId(newExternalId(rng))
                .merchant(merchant)
                .customer(customer)
                .amount(randomBucketedAmount(rng))
                .currency("INR")
                .status(TransactionStatus.ABANDONED)
                .paymentMethod(null)
                .attemptCount(0)
                .createdAt(randomPastInstant(now, rng))
                .updatedAt(now)
                .build();
    }

    private Transaction buildFailedTransaction(Merchant merchant, List<Customer> customers, Random rng, Instant now) {
        Customer customer = randomCustomer(customers, rng);
        FailureCategory failure = randomFailureCategory(rng);
        return Transaction.builder()
                .externalTransactionId(newExternalId(rng))
                .merchant(merchant)
                .customer(customer)
                .amount(randomBucketedAmount(rng))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(randomPaymentMethod(rng))
                .failureCode(failure.name())
                .failureReason(describeFailure(failure))
                .attemptCount(1)
                .createdAt(randomPastInstant(now, rng))
                .updatedAt(now)
                .build();
    }

    private void seedRecoveredTransaction(Merchant merchant, List<Customer> customers, Random rng, Instant now) {
        Customer customer = randomCustomer(customers, rng);
        FailureCategory failure = randomOf(rng, RECOVERABLE_LEANING_FAILURES);
        Instant created = randomPastInstant(now, rng);
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId(newExternalId(rng))
                .merchant(merchant)
                .customer(customer)
                .amount(randomBucketedAmount(rng))
                .currency("INR")
                .status(TransactionStatus.RECOVERED)
                .paymentMethod(randomPaymentMethod(rng))
                .failureCode(failure.name())
                .failureReason(describeFailure(failure))
                .attemptCount(2)
                .createdAt(created)
                .updatedAt(created.plus(1, ChronoUnit.HOURS))
                .build());

        recordRiskAndAudit(txn, created, failure, /* highRisk */ false);

        RecoveryAttempt attempt = recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1)
                .reason("Seed data: automatic retry after " + failure.name())
                .result("Retry succeeded")
                .amountRecovered(txn.getAmount())
                .amount(txn.getAmount())
                .executedAt(created.plus(1, ChronoUnit.HOURS))
                .build());

        auditLogRepository.save(AuditLog.builder()
                .transaction(txn)
                .eventType("RECOVERY_ATTEMPT_RECORDED")
                .actor("SEED_SCRIPT")
                .decision("N/A")
                .reason("Historical seed data representing a successful recovery attempt")
                .metadata(Map.of(
                        "action", attempt.getAction().name(),
                        "status", attempt.getStatus().name(),
                        "amountRecovered", attempt.getAmountRecovered()
                ))
                .timestamp(attempt.getExecutedAt())
                .build());
    }

    private void seedEscalatedTransaction(Merchant merchant, List<Customer> customers, Random rng, Instant now) {
        Customer customer = randomCustomer(customers, rng);
        FailureCategory failure = randomOf(rng, HARD_FAILURES);
        Instant created = randomPastInstant(now, rng);
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId(newExternalId(rng))
                .merchant(merchant)
                .customer(customer)
                .amount(randomBucketedAmount(rng))
                .currency("INR")
                .status(TransactionStatus.ESCALATED)
                .paymentMethod(randomPaymentMethod(rng))
                .failureCode(failure.name())
                .failureReason(describeFailure(failure))
                .attemptCount(2)
                .createdAt(created)
                .updatedAt(created.plus(2, ChronoUnit.HOURS))
                .build());

        recordRiskAndAudit(txn, created, failure, /* highRisk */ true);
        seedRepeatedFailureThenTerminalAction(txn, created, RecoveryAction.ESCALATE, RecoveryAttemptStatus.ESCALATED,
                "Retry limit reached; escalated for manual review");
    }

    private void seedStoppedTransaction(Merchant merchant, List<Customer> customers, Random rng, Instant now) {
        Customer customer = randomCustomer(customers, rng);
        FailureCategory failure = randomOf(rng, HARD_FAILURES);
        Instant created = randomPastInstant(now, rng);
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId(newExternalId(rng))
                .merchant(merchant)
                .customer(customer)
                .amount(randomBucketedAmount(rng))
                .currency("INR")
                .status(TransactionStatus.STOPPED)
                .paymentMethod(randomPaymentMethod(rng))
                .failureCode(failure.name())
                .failureReason(describeFailure(failure))
                .attemptCount(2)
                .createdAt(created)
                .updatedAt(created.plus(2, ChronoUnit.HOURS))
                .build());

        recordRiskAndAudit(txn, created, failure, /* highRisk */ true);
        seedRepeatedFailureThenTerminalAction(txn, created, RecoveryAction.STOP, RecoveryAttemptStatus.BLOCKED,
                "Retry limit reached (max 2 automatic retries) - recovery stopped safely");
    }

    /** Two failed retries followed by a terminal action (ESCALATE or STOP), as historical seed facts. */
    private void seedRepeatedFailureThenTerminalAction(Transaction txn, Instant created, RecoveryAction terminalAction,
                                                         RecoveryAttemptStatus terminalStatus, String terminalReason) {
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(1)
                .reason("Seed data: automatic retry after " + txn.getFailureCode())
                .result("Retry failed - issuer declined again")
                .amount(txn.getAmount())
                .executedAt(created.plus(1, ChronoUnit.HOURS))
                .build());

        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(2)
                .reason("Seed data: second automatic retry after " + txn.getFailureCode())
                .result("Retry failed - issuer declined again")
                .amount(txn.getAmount())
                .executedAt(created.plus(2, ChronoUnit.HOURS))
                .build());

        RecoveryAttempt terminal = recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn)
                .action(terminalAction)
                .status(terminalStatus)
                .attemptNumber(3)
                .reason(terminalReason)
                .result(terminalReason)
                .amount(txn.getAmount())
                .executedAt(created.plus(2, ChronoUnit.HOURS).plusSeconds(30))
                .build());

        auditLogRepository.save(AuditLog.builder()
                .transaction(txn)
                .eventType("RECOVERY_ATTEMPT_RECORDED")
                .actor("SEED_SCRIPT")
                .decision(terminalAction.name())
                .reason("Historical seed data representing " + terminal.getAttemptNumber() + " recorded recovery attempts")
                .metadata(Map.of("finalAction", terminalAction.name(), "finalStatus", terminalStatus.name()))
                .timestamp(terminal.getExecutedAt())
                .build());
    }

    private void recordRiskAndAudit(Transaction txn, Instant created, FailureCategory failure, boolean highRisk) {
        // Derived from the transaction's own external ID rather than a shared
        // Random instance, so this stays deterministic without depending on
        // call order relative to the rest of the generator.
        Random local = new Random(txn.getExternalTransactionId().hashCode());
        BigDecimal riskScore = highRisk
                ? randomAmount(local, 60, 95)
                : randomAmount(local, 20, 55);
        BigDecimal recoveryProbability = highRisk
                ? BigDecimal.valueOf(0.20 + (0.30 * local.nextDouble())).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(0.55 + (0.35 * local.nextDouble())).setScale(4, RoundingMode.HALF_UP);

        RevenueRisk risk = revenueRiskRepository.save(RevenueRisk.builder()
                .transaction(txn)
                .riskScore(riskScore.setScale(2, RoundingMode.HALF_UP))
                .recoveryProbability(recoveryProbability)
                .amountAtRisk(txn.getAmount())
                .reason("Seed heuristic label for demo purposes - failure category " + failure.name()
                        + " (not the Phase 3 risk-scoring engine)")
                .detectedAt(created.plus(5, ChronoUnit.MINUTES))
                .build());

        auditLogRepository.save(AuditLog.builder()
                .transaction(txn)
                .eventType("RISK_DETECTED")
                .actor("SEED_SCRIPT")
                .decision("N/A")
                .reason("Synthetic seed record - transaction flagged as revenue at risk")
                .metadata(Map.of(
                        "riskScore", risk.getRiskScore(),
                        "recoveryProbability", risk.getRecoveryProbability(),
                        "failureCode", failure.name()
                ))
                .timestamp(risk.getDetectedAt())
                .build());
    }

    // ---------------------------------------------------------------- demo transactions

    private void seedDemoTransactions(Merchant merchant, List<Customer> customers, Random rng, Instant now,
                                       Map<String, String> demoTransactionIds) {
        Instant recent = now.minus(2, ChronoUnit.DAYS);

        // Demo 1 - Easy recovery: strong-history customer, temporary failure, low risk.
        Customer strongCustomer = strongestCustomer(customers);
        Transaction easyRecovery = transactionRepository.save(Transaction.builder()
                .externalTransactionId("demo-easy-recovery")
                .merchant(merchant)
                .customer(strongCustomer)
                .amount(new BigDecimal("2499.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .failureReason(describeFailure(FailureCategory.TEMPORARY_FAILURE))
                .attemptCount(1)
                .createdAt(recent)
                .updatedAt(recent)
                .build());
        recordRiskAndAudit(easyRecovery, recent, FailureCategory.TEMPORARY_FAILURE, false);
        demoTransactionIds.put("easy_recovery", easyRecovery.getExternalTransactionId());

        // Demo 2 - Retry failure then escalation.
        Customer mixedCustomer = randomCustomer(customers, rng);
        Transaction retryEscalation = transactionRepository.save(Transaction.builder()
                .externalTransactionId("demo-retry-escalation")
                .merchant(merchant)
                .customer(mixedCustomer)
                .amount(new BigDecimal("3499.00"))
                .currency("INR")
                .status(TransactionStatus.ESCALATED)
                .paymentMethod(PaymentMethod.UPI)
                .failureCode(FailureCategory.BANK_DECLINED.name())
                .failureReason(describeFailure(FailureCategory.BANK_DECLINED))
                .attemptCount(2)
                .createdAt(recent)
                .updatedAt(recent.plus(2, ChronoUnit.HOURS))
                .build());
        recordRiskAndAudit(retryEscalation, recent, FailureCategory.BANK_DECLINED, true);
        seedRepeatedFailureThenTerminalAction(retryEscalation, recent, RecoveryAction.ESCALATE,
                RecoveryAttemptStatus.ESCALATED, "Retry limit reached; escalated for manual review");
        demoTransactionIds.put("retry_then_escalation", retryEscalation.getExternalTransactionId());

        // Demo 3 - High value, will later require human approval.
        Transaction highValue = transactionRepository.save(Transaction.builder()
                .externalTransactionId("demo-high-value")
                .merchant(merchant)
                .customer(strongCustomer)
                .amount(new BigDecimal("47500.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.NETBANKING)
                .failureCode(FailureCategory.INSUFFICIENT_FUNDS.name())
                .failureReason(describeFailure(FailureCategory.INSUFFICIENT_FUNDS))
                .attemptCount(1)
                .createdAt(recent)
                .updatedAt(recent)
                .build());
        recordRiskAndAudit(highValue, recent, FailureCategory.INSUFFICIENT_FUNDS, true);
        demoTransactionIds.put("high_value_requires_approval", highValue.getExternalTransactionId());

        // Demo 4 - Repeated failure that must be safely stopped (matches the
        // failure-recovery walkthrough amount from the product spec).
        Customer weakCustomer = weakestCustomer(customers);
        Transaction repeatedFailure = transactionRepository.save(Transaction.builder()
                .externalTransactionId("demo-repeated-failure")
                .merchant(merchant)
                .customer(weakCustomer)
                .amount(new BigDecimal("7499.00"))
                .currency("INR")
                .status(TransactionStatus.STOPPED)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.BANK_DECLINED.name())
                .failureReason(describeFailure(FailureCategory.BANK_DECLINED))
                .attemptCount(2)
                .createdAt(recent)
                .updatedAt(recent.plus(2, ChronoUnit.HOURS))
                .build());
        recordRiskAndAudit(repeatedFailure, recent, FailureCategory.BANK_DECLINED, true);
        seedRepeatedFailureThenTerminalAction(repeatedFailure, recent, RecoveryAction.STOP,
                RecoveryAttemptStatus.BLOCKED, "Retry limit reached (max 2 automatic retries) - recovery stopped safely");
        demoTransactionIds.put("repeated_failure_stopped", repeatedFailure.getExternalTransactionId());

        // Demo 5 - Clean successful-recovery candidate.
        Transaction successfulRecovery = transactionRepository.save(Transaction.builder()
                .externalTransactionId("demo-successful-recovery")
                .merchant(merchant)
                .customer(strongCustomer)
                .amount(new BigDecimal("1899.00"))
                .currency("INR")
                .status(TransactionStatus.RECOVERED)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.NETWORK_ERROR.name())
                .failureReason(describeFailure(FailureCategory.NETWORK_ERROR))
                .attemptCount(2)
                .createdAt(recent)
                .updatedAt(recent.plus(1, ChronoUnit.HOURS))
                .build());
        recordRiskAndAudit(successfulRecovery, recent, FailureCategory.NETWORK_ERROR, false);
        RecoveryAttempt recoveryAttempt = recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(successfulRecovery)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1)
                .reason("Seed data: automatic retry after " + FailureCategory.NETWORK_ERROR.name())
                .result("Retry succeeded")
                .amountRecovered(successfulRecovery.getAmount())
                .amount(successfulRecovery.getAmount())
                .executedAt(recent.plus(1, ChronoUnit.HOURS))
                .build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(successfulRecovery)
                .eventType("RECOVERY_ATTEMPT_RECORDED")
                .actor("SEED_SCRIPT")
                .decision("N/A")
                .reason("Historical seed data representing a successful recovery attempt")
                .metadata(Map.of("amountRecovered", recoveryAttempt.getAmountRecovered()))
                .timestamp(recoveryAttempt.getExecutedAt())
                .build());
        demoTransactionIds.put("successful_recovery", successfulRecovery.getExternalTransactionId());
    }

    private Customer strongestCustomer(List<Customer> customers) {
        return customers.stream()
                .max((a, b) -> Integer.compare(
                        a.getSuccessfulPaymentCount() - a.getFailedPaymentCount(),
                        b.getSuccessfulPaymentCount() - b.getFailedPaymentCount()))
                .orElseThrow();
    }

    private Customer weakestCustomer(List<Customer> customers) {
        return customers.stream()
                .max((a, b) -> Integer.compare(a.getFailedPaymentCount(), b.getFailedPaymentCount()))
                .orElseThrow();
    }

    // ---------------------------------------------------------------- shared helpers

    private Customer randomCustomer(List<Customer> customers, Random rng) {
        return customers.get(rng.nextInt(customers.size()));
    }

    private PaymentMethod randomPaymentMethod(Random rng) {
        PaymentMethod[] methods = PaymentMethod.values();
        return methods[rng.nextInt(methods.length)];
    }

    private FailureCategory randomFailureCategory(Random rng) {
        FailureCategory[] categories = FailureCategory.values();
        return categories[rng.nextInt(categories.length)];
    }

    private FailureCategory randomOf(Random rng, FailureCategory[] pool) {
        return pool[rng.nextInt(pool.length)];
    }

    /** 60% mid-value, 25% low-value, 15% high-value. */
    private BigDecimal randomBucketedAmount(Random rng) {
        double roll = rng.nextDouble();
        if (roll < 0.25) return randomAmount(rng, 100, 999);
        if (roll < 0.85) return randomAmount(rng, 1000, 9999);
        return randomAmount(rng, 10000, 99999);
    }

    private BigDecimal randomAmount(Random rng, int minInclusive, int maxInclusive) {
        double value = minInclusive + rng.nextDouble() * (maxInclusive - minInclusive);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private Instant randomPastInstant(Instant now, Random rng) {
        long secondsAgo = (long) (rng.nextDouble() * DAYS_SPAN * 24 * 3600);
        return now.minusSeconds(secondsAgo);
    }

    private String newExternalId(Random rng) {
        return "txn_" + Long.toHexString(rng.nextLong() & Long.MAX_VALUE);
    }

    private String describeFailure(FailureCategory category) {
        return switch (category) {
            case TEMPORARY_FAILURE -> "Payment gateway reported a temporary failure; likely recoverable on retry.";
            case INSUFFICIENT_FUNDS -> "Issuing bank declined due to insufficient funds at time of charge.";
            case BANK_DECLINED -> "Issuing bank declined the transaction without a specific reason.";
            case NETWORK_ERROR -> "Network error during payment processing; no charge was completed.";
            case AUTHENTICATION_FAILURE -> "Customer failed 3-D Secure / OTP authentication.";
            case LIMIT_EXCEEDED -> "Transaction exceeded the card's per-transaction or daily limit.";
            case UNKNOWN -> "Payment failed for an unspecified reason.";
        };
    }

    // ---------------------------------------------------------------- report

    private SeedReport buildReport(Map<String, String> demoTransactionIds) {
        Map<String, Long> countsByStatus = new LinkedHashMap<>();
        for (TransactionStatus status : TransactionStatus.values()) {
            countsByStatus.put(status.name(), transactionRepository.countByStatus(status));
        }
        long total = countsByStatus.values().stream().mapToLong(Long::longValue).sum();

        List<Transaction> all = transactionRepository.findAll();
        long highValueCount = all.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(10000)) >= 0)
                .count();
        long repeatedFailureCount = all.stream()
                .filter(t -> t.getAttemptCount() >= 2)
                .count();

        return new SeedReport(
                (int) total,
                countsByStatus,
                highValueCount,
                repeatedFailureCount,
                revenueRiskRepository.count(),
                recoveryAttemptRepository.count(),
                auditLogRepository.count(),
                demoTransactionIds
        );
    }
}
