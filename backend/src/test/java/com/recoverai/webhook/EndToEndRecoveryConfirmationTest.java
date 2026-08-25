package com.recoverai.webhook;

import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RecoveryMetricsResponse;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.execution.RecoveryMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the complete intended recovery lifecycle end to end, over real
 * HTTP, using the deterministic mock provider (no real Razorpay Test Mode
 * credentials are available in this environment - see docs/ARCHITECTURE.md
 * "Real Razorpay Test Mode - what is and isn't verified here"):
 * <p>
 * AI recommendation -&gt; policy ALLOW -&gt; execution (mock gateway) -&gt;
 * provider reference -&gt; signed webhook -&gt; signature/amount/currency
 * verification -&gt; RecoveryAttempt confirmed -&gt; amountRecovered &gt; 0 -&gt;
 * Transaction.status = RECOVERED -&gt; recovery metrics updated -&gt; audit
 * trail updated.
 * <p>
 * Every other test in this package exercises {@link PaymentConfirmationService}
 * directly against a hand-seeded "already executed" attempt; this is the one
 * test that drives the real {@code RecoveryExecutionService} first, so the
 * providerReference/amount the webhook confirms is whatever the execution
 * pipeline actually produced, not a fixture value chosen to match.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "e2e-admin", roles = "MERCHANT_ADMIN")
class EndToEndRecoveryConfirmationTest {

    private static final String SECRET = "test_webhook_secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private RecoveryMetricsService recoveryMetricsService;

    @Test
    void fullLifecycle_executionThenSignedWebhook_confirmsRecoveryAndUpdatesMetrics() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("E2E Merchant").email("e2e-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("E2E Customer").email("e2e-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(6).failedPaymentCount(0).build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_e2e_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("1500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD)
                .failureCode("TEMPORARY_FAILURE").attemptCount(1).build());

        RecoveryMetricsResponse before = recoveryMetricsService.getMetrics();

        // 1. AI recommendation -> policy ALLOW -> execution (mock gateway) -> provider reference.
        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.policyDecision.decision").value("ALLOW"))
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.paymentConfirmationStatus").value("NOT_CONFIRMED"))
                .andExpect(jsonPath("$.amountRecovered").value(0.00));

        // Execution success alone must never confirm anything.
        Transaction afterExecution = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(afterExecution.getStatus()).isEqualTo(TransactionStatus.FAILED);

        List<RecoveryAttempt> attempts = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transaction.getId());
        assertThat(attempts).hasSize(1);
        RecoveryAttempt attempt = attempts.get(0);
        assertThat(attempt.getPaymentConfirmationStatus()).isEqualTo(PaymentConfirmationStatus.NOT_CONFIRMED);
        assertThat(attempt.getProviderReference()).isNotBlank();

        // 2. A genuinely signed webhook, referencing the real providerReference the execution just produced.
        long amountPaise = attempt.getAmount().movePointRight(2).longValueExact();
        String payload = """
                {"event":"payment_link.paid","payload":{\
                "payment_link":{"entity":{"id":"%s"}},\
                "payment":{"entity":{"id":"pay_e2e_test","amount":%d,"currency":"INR"}}}}\
                """.formatted(attempt.getProviderReference(), amountPaise);
        String signature = RazorpayWebhookSignature.sign(payload, SECRET);

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", signature)
                        .header("X-Razorpay-Event-Id", "evt_e2e_" + UUID.randomUUID())
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 3. RecoveryAttempt confirmed, amountRecovered > 0, Transaction RECOVERED.
        RecoveryAttempt confirmedAttempt = recoveryAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(confirmedAttempt.getPaymentConfirmationStatus()).isEqualTo(PaymentConfirmationStatus.CONFIRMED);
        assertThat(confirmedAttempt.getAmountRecovered()).isGreaterThan(BigDecimal.ZERO);
        assertThat(confirmedAttempt.getConfirmedAmount()).isEqualByComparingTo(attempt.getAmount());

        Transaction confirmedTransaction = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(confirmedTransaction.getStatus()).isEqualTo(TransactionStatus.RECOVERED);

        // 4. Recovery metrics reflect the confirmation, not just the execution.
        RecoveryMetricsResponse after = recoveryMetricsService.getMetrics();
        assertThat(after.confirmedRecoveryCount()).isEqualTo(before.confirmedRecoveryCount() + 1);
        assertThat(after.confirmedRecoveredRevenue()).isEqualByComparingTo(
                before.confirmedRecoveredRevenue().add(attempt.getAmount()));
        assertThat(after.transactionsRecovered()).isEqualTo(before.transactionsRecovered() + 1);

        // 5. Audit trail records both the execution and the confirmation.
        mockMvc.perform(get("/api/audit/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType == 'RECOVERY_EXECUTION_COMPLETED')]").exists())
                .andExpect(jsonPath("$[?(@.eventType == 'PAYMENT_RECOVERY_CONFIRMED')]").exists());
    }
}
