package com.recoverai.risk;

import com.recoverai.config.RevenueRiskProperties;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RevenueRiskResponse;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RevenueRiskServiceTest {

    @Autowired
    private RevenueRiskService revenueRiskService;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RevenueRiskRepository revenueRiskRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    private Merchant merchant;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Risk Test Merchant")
                .email("risk-" + UUID.randomUUID() + "@example.com")
                .build());
    }

    // ---------------------------------------------------------------- pure helper functions (no Spring context needed)

    @Test
    void historyScore_noHistory_isNeutral() {
        RevenueRiskService plain = new RevenueRiskService(null, null, null, new RevenueRiskProperties());
        Customer noHistory = Customer.builder().successfulPaymentCount(0).failedPaymentCount(0).build();
        assertThat(plain.historyScore(noHistory)).isEqualByComparingTo("0.5");
    }

    @Test
    void historyScore_allSuccess_isHigh() {
        RevenueRiskService plain = new RevenueRiskService(null, null, null, new RevenueRiskProperties());
        Customer strong = Customer.builder().successfulPaymentCount(10).failedPaymentCount(0).build();
        // (10+1)/(10+0+2) = 11/12
        assertThat(plain.historyScore(strong)).isGreaterThan(new BigDecimal("0.75"));
    }

    @Test
    void historyScore_allFailure_isLow() {
        RevenueRiskService plain = new RevenueRiskService(null, null, null, new RevenueRiskProperties());
        Customer weak = Customer.builder().successfulPaymentCount(0).failedPaymentCount(10).build();
        assertThat(plain.historyScore(weak)).isLessThan(new BigDecimal("0.35"));
    }

    @Test
    void amountFactor_boundariesUseCorrectBucket() {
        RevenueRiskService plain = new RevenueRiskService(null, null, null, new RevenueRiskProperties());
        assertThat(plain.amountFactor(new BigDecimal("500"))).isEqualByComparingTo("0.10");
        assertThat(plain.amountFactor(new BigDecimal("5000"))).isEqualByComparingTo("0.30");
        assertThat(plain.amountFactor(new BigDecimal("20000"))).isEqualByComparingTo("0.60");
        assertThat(plain.amountFactor(new BigDecimal("80000"))).isEqualByComparingTo("0.92");
    }

    @Test
    void resolveFailureCategory_unrecognizedCode_fallsBackToUnknown() {
        RevenueRiskService plain = new RevenueRiskService(null, null, null, new RevenueRiskProperties());
        Transaction txn = Transaction.builder().failureCode("SOME_UNMAPPED_GATEWAY_CODE").build();
        assertThat(plain.resolveFailureCategory(txn)).isEqualTo(FailureCategory.UNKNOWN);
    }

    @Test
    void resolveFailureCategory_nullCode_isUnknown() {
        RevenueRiskService plain = new RevenueRiskService(null, null, null, new RevenueRiskProperties());
        Transaction txn = Transaction.builder().failureCode(null).build();
        assertThat(plain.resolveFailureCategory(txn)).isEqualTo(FailureCategory.UNKNOWN);
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
                .externalTransactionId("txn_" + UUID.randomUUID())
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

    private void addFailedRecoveryAttempt(Transaction transaction, int attemptNumber) {
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(attemptNumber)
                .amount(transaction.getAmount())
                .build());
    }

    // ---------------------------------------------------------------- amount-at-risk rules (section 9 / 23)

    @Test
    void failedTransaction_amountAtRiskEqualsTransactionAmount() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.amountAtRisk()).isEqualByComparingTo("2499.00");
    }

    @Test
    void recoveredTransaction_amountAtRiskIsZero_andRiskLevelLow() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.RECOVERED, new BigDecimal("1899.00"),
                FailureCategory.NETWORK_ERROR, 2);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.amountAtRisk()).isEqualByComparingTo("0.00");
        assertThat(response.riskScore()).isEqualByComparingTo("0.00");
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(response.recoveryProbability()).isEqualByComparingTo("1.0000");
        assertThat(response.factors()).containsExactly("REVENUE_RECOVERED");
    }

    @Test
    void successfulTransaction_isNeverTreatedAsAtRisk() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.SUCCESS, new BigDecimal("999.00"), null, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.amountAtRisk()).isEqualByComparingTo("0.00");
        assertThat(response.factors()).containsExactly("TRANSACTION_SUCCESSFUL");
    }

    // ---------------------------------------------------------------- risk score vs recovery probability distinction (section 12)

    @Test
    void highAmountAtRisk_doesNotImplyLowRecoveryProbability() {
        Customer strong = customer(10, 0);
        Transaction highValueEasyFailure = transaction(strong, TransactionStatus.FAILED, new BigDecimal("47500.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(highValueEasyFailure.getId());

        assertThat(response.riskLevel()).isIn(RiskLevel.HIGH, RiskLevel.CRITICAL);
        assertThat(response.recoveryProbability())
                .as("a large, easily-recoverable failure should still show high recovery probability")
                .isGreaterThanOrEqualTo(new BigDecimal("0.60"));
    }

    // ---------------------------------------------------------------- factor / reason generation

    @Test
    void temporaryFailureWithStrongHistory_producesEasyRecoveryReason() {
        Customer strong = customer(10, 0);
        Transaction txn = transaction(strong, TransactionStatus.FAILED, new BigDecimal("2499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.factors()).contains("TEMPORARY_FAILURE", "STRONG_CUSTOMER_HISTORY", "AMOUNT_AT_RISK");
        assertThat(response.reason())
                .isEqualTo("Temporary failure with strong customer history creates a high recovery opportunity.");
    }

    @Test
    void repeatedFailuresWithWeakHistory_flagsRepeatedFailureFactors() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.ESCALATED, new BigDecimal("3499.00"),
                FailureCategory.BANK_DECLINED, 2);
        addFailedRecoveryAttempt(txn, 1);
        addFailedRecoveryAttempt(txn, 2);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.factors()).contains(
                "WEAK_CUSTOMER_HISTORY", "REPEATED_PAYMENT_FAILURE", "MULTIPLE_PREVIOUS_ATTEMPTS",
                "ESCALATED_AWAITING_MANUAL_REVIEW");
        assertThat(response.reason())
                .isEqualTo("Automated recovery attempts were exhausted; this transaction is awaiting manual review.");
    }

    @Test
    void stoppedTransaction_flagsAutomatedRecoveryStopped() {
        Customer weak = customer(0, 8);
        Transaction txn = transaction(weak, TransactionStatus.STOPPED, new BigDecimal("7499.00"),
                FailureCategory.BANK_DECLINED, 2);
        addFailedRecoveryAttempt(txn, 1);
        addFailedRecoveryAttempt(txn, 2);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.factors()).contains("AUTOMATED_RECOVERY_STOPPED");
        assertThat(response.reason()).isEqualTo(
                "Automated recovery was safely stopped after repeated failures; revenue remains uncollected.");
    }

    @Test
    void pendingTransaction_usesPendingBaseline() {
        Customer c = customer(3, 1);
        Transaction txn = transaction(c, TransactionStatus.PENDING, new BigDecimal("1500.00"), null, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.factors()).contains("PAYMENT_PENDING_CONFIRMATION");
        assertThat(response.reason()).isEqualTo("Payment is pending confirmation; outcome not yet determined.");
    }

    @Test
    void abandonedTransaction_usesAbandonedBaseline() {
        Customer c = customer(3, 1);
        Transaction txn = transaction(c, TransactionStatus.ABANDONED, new BigDecimal("1500.00"), null, 0);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.factors()).contains("CHECKOUT_ABANDONED");
        assertThat(response.reason()).isEqualTo(
                "Checkout was abandoned before payment completion; recovery requires re-engagement.");
    }

    @Test
    void unknownFailureCategory_isFlaggedAndConservative() {
        Customer c = customer(2, 2);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("1500.00"),
                FailureCategory.UNKNOWN, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(response.factors()).contains("UNKNOWN_FAILURE_REASON");
    }

    // ---------------------------------------------------------------- idempotency (section 17)

    @Test
    void reanalyzingSameTransaction_updatesExistingRowRatherThanDuplicating() {
        Customer c = customer(5, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("999.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        revenueRiskService.analyzeTransaction(txn.getId());
        UUID firstRowId = revenueRiskRepository.findByTransactionId(txn.getId()).orElseThrow().getId();
        long countAfterFirst = revenueRiskRepository.count();

        revenueRiskService.analyzeTransaction(txn.getId());
        UUID secondRowId = revenueRiskRepository.findByTransactionId(txn.getId()).orElseThrow().getId();
        long countAfterSecond = revenueRiskRepository.count();

        assertThat(secondRowId).isEqualTo(firstRowId);
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    // ---------------------------------------------------------------- determinism (section 25)

    @Test
    void analyzingSameTransactionTwice_producesIdenticalScores() {
        Customer c = customer(4, 2);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("6250.00"),
                FailureCategory.INSUFFICIENT_FUNDS, 1);

        RevenueRiskResponse first = revenueRiskService.analyzeTransaction(txn.getId());
        RevenueRiskResponse second = revenueRiskService.analyzeTransaction(txn.getId());

        assertThat(second.riskScore()).isEqualByComparingTo(first.riskScore());
        assertThat(second.recoveryProbability()).isEqualByComparingTo(first.recoveryProbability());
        assertThat(second.amountAtRisk()).isEqualByComparingTo(first.amountAtRisk());
        assertThat(second.riskLevel()).isEqualTo(first.riskLevel());
        assertThat(second.factors()).isEqualTo(first.factors());
    }

    // ---------------------------------------------------------------- misc edge cases

    @Test
    void unanalyzedTransaction_throwsNotFoundForUnknownId() {
        assertThatThrownBy(() -> revenueRiskService.analyzeTransaction(UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void potentialRecoveryValue_isAmountAtRiskTimesRecoveryProbability() {
        Customer c = customer(8, 0);
        Transaction txn = transaction(c, TransactionStatus.FAILED, new BigDecimal("7499.00"),
                FailureCategory.TEMPORARY_FAILURE, 1);

        RevenueRiskResponse response = revenueRiskService.analyzeTransaction(txn.getId());

        BigDecimal expected = response.amountAtRisk().multiply(response.recoveryProbability())
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(response.potentialRecoveryValue()).isEqualByComparingTo(expected);
    }
}
