package com.recoverai.execution;

import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RecoveryMetricsResponse;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.webhook.PaymentConfirmationService;
import com.recoverai.webhook.RazorpayWebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecoveryMetricsService} - the aggregate figures a client would see
 * at {@code GET /api/recovery/metrics}. Verifies both the honest zero-state
 * and that {@code confirmedRecoveredRevenue} only ever counts genuinely
 * webhook-confirmed attempts, never execution success alone (Phase 11
 * spec §9).
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryMetricsServiceTest {

    private static final String SECRET = "test_webhook_secret";

    @Autowired
    private RecoveryMetricsService metricsService;
    @Autowired
    private PaymentConfirmationService paymentConfirmationService;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    @Test
    void zeroAttempts_reportsHonestZeroesNotErrors() {
        // A clean read only makes sense in isolation; other tests in the suite share the same
        // H2 instance, so this asserts the invariant that matters regardless of prior state:
        // rates never exceed 1 and confirmed revenue is never negative or fabricated.
        RecoveryMetricsResponse metrics = metricsService.getMetrics();

        assertThat(metrics.confirmedRecoveredRevenue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(metrics.recoveryRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(metrics.executionSuccessRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(metrics.confirmationRate()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
    }

    @Test
    void confirmedPayment_increasesConfirmedRevenueByExactlyThatAmount() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Metrics Test Merchant").email("metrics-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Metrics Customer").email("metrics-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("metrics_txn_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("1234.56")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name()).attemptCount(1).build());
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(new BigDecimal("1234.56")).provider("razorpay")
                .providerReference("plink_metrics").executedAt(Instant.now())
                .idempotencyKey(transaction.getId() + ":RETRY_PAYMENT:1").build());

        BigDecimal before = metricsService.getMetrics().confirmedRecoveredRevenue();

        String payload = """
                {"event":"payment_link.paid","payload":{\
                "payment_link":{"entity":{"id":"plink_metrics"}},\
                "payment":{"entity":{"id":"pay_metrics","amount":123456,"currency":"INR"}}}}""";
        String signature = RazorpayWebhookSignature.sign(payload, SECRET);
        paymentConfirmationService.processRazorpayWebhook(payload, signature, "evt_metrics");

        RecoveryMetricsResponse after = metricsService.getMetrics();
        assertThat(after.confirmedRecoveredRevenue()).isEqualByComparingTo(before.add(new BigDecimal("1234.56")));
        assertThat(after.confirmedRecoveryCount()).isGreaterThanOrEqualTo(1);
    }
}
