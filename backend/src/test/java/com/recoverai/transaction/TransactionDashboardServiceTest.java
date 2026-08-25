package com.recoverai.transaction;

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
import com.recoverai.dto.TransactionFullDetailResponse;
import com.recoverai.dto.TransactionListItemResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The general-purpose transaction dashboard (Phase 13): combinable
 * filters, search, sorting, pagination, and the bundled detail view -
 * exercised over transactions this test creates itself, not the 5 curated
 * demo scenarios, proving the dashboard genuinely works for "any
 * transaction."
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionDashboardServiceTest {

    @Autowired
    private TransactionDashboardService service;
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
    private Customer customer;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Dashboard Test Merchant").email("dash-" + UUID.randomUUID() + "@example.com").build());
        customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Dashboard Test Customer").email("dash-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(4).failedPaymentCount(1).totalHistoricalValue(new BigDecimal("9000.00")).build());
    }

    private Transaction transaction(TransactionStatus status, BigDecimal amount, String externalId, FailureCategory category) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId(externalId).merchant(merchant).customer(customer).amount(amount).currency("INR")
                .status(status).paymentMethod(PaymentMethod.CARD)
                .failureCode(category == null ? null : category.name()).attemptCount(1).build());
    }

    private RevenueRisk risk(Transaction t, RiskLevel level, BigDecimal riskScore, BigDecimal amountAtRisk, BigDecimal recoveryProbability) {
        return revenueRiskRepository.save(RevenueRisk.builder()
                .transaction(t).riskLevel(level).riskScore(riskScore).amountAtRisk(amountAtRisk)
                .recoveryProbability(recoveryProbability).factors(List.of("TEST_FACTOR")).reason("test fixture")
                .detectedAt(Instant.now()).build());
    }

    // ---------------------------------------------------------------- "not analyzed"

    @Test
    void unanalyzedTransaction_reportsNullRiskNotFabricatedValues() {
        transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), "dash_unanalyzed_" + UUID.randomUUID(), FailureCategory.TEMPORARY_FAILURE);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                null, false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 50));

        TransactionListItemResponse found = page.getContent().stream()
                .filter(r -> r.externalTransactionId().startsWith("dash_unanalyzed_")).findFirst().orElseThrow();
        assertThat(found.riskScore()).isNull();
        assertThat(found.riskLevel()).isNull();
        assertThat(found.amountAtRisk()).isNull();
        assertThat(found.latestRecoveryAction()).isNull();
    }

    // ---------------------------------------------------------------- filters

    @Test
    void filterByStatus_onlyReturnsMatchingStatus() {
        String id = "dash_status_" + UUID.randomUUID();
        transaction(TransactionStatus.ABANDONED, new BigDecimal("100.00"), id, FailureCategory.UNKNOWN);

        Page<TransactionListItemResponse> page = service.search(TransactionStatus.ABANDONED, null, null, null,
                null, null, id, false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).status()).isEqualTo(TransactionStatus.ABANDONED);
    }

    @Test
    void filterByRiskLevel_joinsRealRiskData() {
        Transaction high = transaction(TransactionStatus.FAILED, new BigDecimal("40000.00"), "dash_risk_high_" + UUID.randomUUID(), FailureCategory.BANK_DECLINED);
        risk(high, RiskLevel.HIGH, new BigDecimal("75.00"), new BigDecimal("40000.00"), new BigDecimal("0.4000"));
        Transaction low = transaction(TransactionStatus.FAILED, new BigDecimal("100.00"), "dash_risk_low_" + UUID.randomUUID(), FailureCategory.TEMPORARY_FAILURE);
        risk(low, RiskLevel.LOW, new BigDecimal("10.00"), new BigDecimal("100.00"), new BigDecimal("0.9000"));

        Page<TransactionListItemResponse> page = service.search(null, RiskLevel.HIGH, null, null, null, null,
                "dash_risk_", false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::riskLevel).containsOnly(RiskLevel.HIGH);
    }

    @Test
    void atRiskOnly_excludesTransactionsWithNoOrZeroAmountAtRisk() {
        String prefix = "dash_atrisk_" + UUID.randomUUID();
        Transaction atRisk = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix + "_yes", FailureCategory.TEMPORARY_FAILURE);
        risk(atRisk, RiskLevel.MEDIUM, new BigDecimal("40.00"), new BigDecimal("500.00"), new BigDecimal("0.7000"));
        Transaction resolved = transaction(TransactionStatus.SUCCESS, new BigDecimal("500.00"), prefix + "_no", null);
        risk(resolved, RiskLevel.LOW, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                prefix, true, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::externalTransactionId)
                .containsExactly(prefix + "_yes");
    }

    @Test
    void amountRange_filtersInclusively() {
        String prefix = "dash_amount_" + UUID.randomUUID();
        transaction(TransactionStatus.FAILED, new BigDecimal("1000.00"), prefix + "_low", FailureCategory.UNKNOWN);
        transaction(TransactionStatus.FAILED, new BigDecimal("5000.00"), prefix + "_mid", FailureCategory.UNKNOWN);
        transaction(TransactionStatus.FAILED, new BigDecimal("9000.00"), prefix + "_high", FailureCategory.UNKNOWN);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null,
                new BigDecimal("2000.00"), new BigDecimal("6000.00"), prefix, false, false, null,
                TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::externalTransactionId)
                .containsExactly(prefix + "_mid");
    }

    // ---------------------------------------------------------------- search

    @Test
    void search_matchesExternalTransactionIdSubstring_caseInsensitive() {
        String unique = "DashSearchable" + UUID.randomUUID();
        transaction(TransactionStatus.FAILED, new BigDecimal("250.00"), unique, FailureCategory.UNKNOWN);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                unique.toLowerCase(), false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::externalTransactionId).containsExactly(unique);
    }

    @Test
    void search_matchesTransactionIdExactly() {
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("250.00"), "dash_id_search_" + UUID.randomUUID(), FailureCategory.UNKNOWN);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                t.getId().toString(), false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::id).containsExactly(t.getId());
    }

    @Test
    void search_matchesCustomerIdExactly() {
        String prefix = "dash_cust_search_" + UUID.randomUUID();
        transaction(TransactionStatus.FAILED, new BigDecimal("250.00"), prefix, FailureCategory.UNKNOWN);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                customer.getId().toString(), false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::externalTransactionId).contains(prefix);
    }

    // ---------------------------------------------------------------- sorting + pagination

    @Test
    void sortByAmountDescending_ordersCorrectly() {
        String prefix = "dash_sort_" + UUID.randomUUID();
        transaction(TransactionStatus.FAILED, new BigDecimal("100.00"), prefix + "_a", FailureCategory.UNKNOWN);
        transaction(TransactionStatus.FAILED, new BigDecimal("900.00"), prefix + "_b", FailureCategory.UNKNOWN);
        transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix + "_c", FailureCategory.UNKNOWN);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                prefix, false, false, null, TransactionSort.AMOUNT_DESC, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(r -> r.amount().intValue()).containsExactly(900, 500, 100);
    }

    @Test
    void pagination_respectsPageSizeAndReportsTotalCount() {
        String prefix = "dash_page_" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            transaction(TransactionStatus.FAILED, new BigDecimal("100.00"), prefix + "_" + i, FailureCategory.UNKNOWN);
        }

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                prefix, false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- latest recovery attempt

    @Test
    void listItem_showsLatestRecoveryAttemptByAttemptNumber() {
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), "dash_latest_" + UUID.randomUUID(), FailureCategory.TEMPORARY_FAILURE);
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(t).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(1).amount(t.getAmount()).executedAt(Instant.now().minusSeconds(100))
                .idempotencyKey(t.getId() + ":RETRY_PAYMENT:1").build());
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(t).action(RecoveryAction.CREATE_PAYMENT_LINK).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(2).amount(t.getAmount()).executedAt(Instant.now())
                .idempotencyKey(t.getId() + ":CREATE_PAYMENT_LINK:2").build());

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                t.getExternalTransactionId(), false, false, null, TransactionSort.NEWEST, PageRequest.of(0, 10));

        TransactionListItemResponse item = page.getContent().get(0);
        assertThat(item.latestRecoveryAction()).isEqualTo(RecoveryAction.CREATE_PAYMENT_LINK);
        assertThat(item.latestRecoveryStatus()).isEqualTo(RecoveryAttemptStatus.SUCCESS);
    }

    // ---------------------------------------------------------------- recoveryAttemptStatus filter (latest attempt only)

    private RecoveryAttempt attempt(Transaction t, int number, RecoveryAttemptStatus status, Instant executedAt) {
        return recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(t).action(RecoveryAction.RETRY_PAYMENT).status(status)
                .attemptNumber(number).amount(t.getAmount()).executedAt(executedAt)
                .idempotencyKey(t.getId() + ":RETRY_PAYMENT:" + number).build());
    }

    @Test
    void latestAttemptFilter_matchesLatestStatus_notAnEarlierOne() {
        String prefix = "dash_latest_filter_" + UUID.randomUUID();
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix, FailureCategory.TEMPORARY_FAILURE);
        attempt(t, 1, RecoveryAttemptStatus.FAILED, Instant.now().minusSeconds(100));
        attempt(t, 2, RecoveryAttemptStatus.SUCCESS, Instant.now());

        Page<TransactionListItemResponse> matchingSuccess = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.SUCCESS, TransactionSort.NEWEST, PageRequest.of(0, 10));
        assertThat(matchingSuccess.getContent()).extracting(TransactionListItemResponse::id).containsExactly(t.getId());

        Page<TransactionListItemResponse> matchingFailed = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.FAILED, TransactionSort.NEWEST, PageRequest.of(0, 10));
        assertThat(matchingFailed.getContent()).isEmpty();
    }

    @Test
    void latestAttemptFilter_noAttempts_neverMatches() {
        String prefix = "dash_latest_none_" + UUID.randomUUID();
        transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix, FailureCategory.TEMPORARY_FAILURE);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.SUCCESS, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void latestAttemptFilter_oneAttempt_matchesItsOwnStatus() {
        String prefix = "dash_latest_one_" + UUID.randomUUID();
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix, FailureCategory.TEMPORARY_FAILURE);
        attempt(t, 1, RecoveryAttemptStatus.SUCCESS, Instant.now());

        Page<TransactionListItemResponse> matching = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.SUCCESS, TransactionSort.NEWEST, PageRequest.of(0, 10));
        assertThat(matching.getContent()).extracting(TransactionListItemResponse::id).containsExactly(t.getId());

        Page<TransactionListItemResponse> notMatching = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.FAILED, TransactionSort.NEWEST, PageRequest.of(0, 10));
        assertThat(notMatching.getContent()).isEmpty();
    }

    @Test
    void latestAttemptFilter_threeAttempts_onlyTheLatestCounts() {
        String prefix = "dash_latest_three_" + UUID.randomUUID();
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix, FailureCategory.TEMPORARY_FAILURE);
        attempt(t, 1, RecoveryAttemptStatus.FAILED, Instant.now().minusSeconds(200));
        attempt(t, 2, RecoveryAttemptStatus.BLOCKED, Instant.now().minusSeconds(100));
        attempt(t, 3, RecoveryAttemptStatus.SUCCESS, Instant.now());

        assertThat(service.search(null, null, null, null, null, null, prefix, false, false,
                RecoveryAttemptStatus.SUCCESS, TransactionSort.NEWEST, PageRequest.of(0, 10)).getContent())
                .extracting(TransactionListItemResponse::id).containsExactly(t.getId());
        assertThat(service.search(null, null, null, null, null, null, prefix, false, false,
                RecoveryAttemptStatus.FAILED, TransactionSort.NEWEST, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(service.search(null, null, null, null, null, null, prefix, false, false,
                RecoveryAttemptStatus.BLOCKED, TransactionSort.NEWEST, PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    void latestAttemptFilter_equalTimestamps_stillDeterminedByAttemptNumberNotTime() {
        String prefix = "dash_latest_tie_" + UUID.randomUUID();
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix, FailureCategory.TEMPORARY_FAILURE);
        Instant sameInstant = Instant.now();
        attempt(t, 1, RecoveryAttemptStatus.FAILED, sameInstant);
        attempt(t, 2, RecoveryAttemptStatus.SUCCESS, sameInstant);

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.SUCCESS, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::id).containsExactly(t.getId());
    }

    @Test
    void latestAttemptFilter_multipleTransactions_onlyTheMatchingOneReturned() {
        String prefix = "dash_latest_multi_" + UUID.randomUUID();
        Transaction succeeding = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix + "_a", FailureCategory.TEMPORARY_FAILURE);
        attempt(succeeding, 1, RecoveryAttemptStatus.SUCCESS, Instant.now());
        Transaction failing = transaction(TransactionStatus.FAILED, new BigDecimal("500.00"), prefix + "_b", FailureCategory.TEMPORARY_FAILURE);
        attempt(failing, 1, RecoveryAttemptStatus.FAILED, Instant.now());

        Page<TransactionListItemResponse> page = service.search(null, null, null, null, null, null,
                prefix, false, false, RecoveryAttemptStatus.SUCCESS, TransactionSort.NEWEST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(TransactionListItemResponse::id).containsExactly(succeeding.getId());
    }

    // ---------------------------------------------------------------- detail view

    @Test
    void fullDetail_bundlesTransactionCustomerRiskRecoveryAndAudit() {
        Transaction t = transaction(TransactionStatus.FAILED, new BigDecimal("750.00"), "dash_detail_" + UUID.randomUUID(), FailureCategory.NETWORK_ERROR);
        risk(t, RiskLevel.MEDIUM, new BigDecimal("45.00"), new BigDecimal("750.00"), new BigDecimal("0.6500"));
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(t).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(t.getAmount()).provider("mock").executedAt(Instant.now())
                .idempotencyKey(t.getId() + ":RETRY_PAYMENT:1").build());

        TransactionFullDetailResponse detail = service.getFullDetail(t.getId());

        assertThat(detail.transaction().id()).isEqualTo(t.getId());
        assertThat(detail.customerSuccessfulPaymentCount()).isEqualTo(4);
        assertThat(detail.customerTotalHistoricalValue()).isEqualByComparingTo("9000.00");
        assertThat(detail.customerRecoveryContactAllowed()).isTrue();
        assertThat(detail.risk()).isNotNull();
        assertThat(detail.risk().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(detail.recoveryAttempts()).hasSize(1);
        assertThat(detail.recoveryAttempts().get(0).status()).isEqualTo(RecoveryAttemptStatus.SUCCESS);
    }

    @Test
    void fullDetail_unanalyzedTransaction_hasNullRisk() {
        Transaction t = transaction(TransactionStatus.PENDING, new BigDecimal("300.00"), "dash_detail_no_risk_" + UUID.randomUUID(), null);

        TransactionFullDetailResponse detail = service.getFullDetail(t.getId());

        assertThat(detail.risk()).isNull();
        assertThat(detail.recoveryAttempts()).isEmpty();
    }

    @Test
    void fullDetail_unknownTransaction_throwsNotFound() {
        assertThatThrownBy(() -> service.getFullDetail(UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
    }
}
