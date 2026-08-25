package com.recoverai.controller;

import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.webhook.RazorpayWebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-boundary tests for {@code POST /api/webhooks/razorpay} - signature
 * gating over real HTTP, and that a valid, correctly-signed request is
 * genuinely processed end to end (200, transaction RECOVERED). Business
 * -logic edge cases live in {@code PaymentConfirmationServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookControllerTest {

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

    private Transaction transactionWithSuccessfulAttempt(String providerReference, BigDecimal amount) {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Webhook HTTP Test Merchant").email("webhook-http-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Webhook HTTP Customer")
                .email("webhook-http-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("webhook_http_txn_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(amount).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name()).attemptCount(1).build());
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(amount).provider("razorpay").providerReference(providerReference)
                .executedAt(Instant.now()).idempotencyKey(transaction.getId() + ":RETRY_PAYMENT:1").build());
        return transaction;
    }

    private static String payload(String paymentLinkId, String paymentId, long amountPaise, String currency) {
        return """
                {"event":"payment_link.paid","payload":{\
                "payment_link":{"entity":{"id":"%s"}},\
                "payment":{"entity":{"id":"%s","amount":%d,"currency":"%s"}}}}\
                """.formatted(paymentLinkId, paymentId, amountPaise, currency);
    }

    @Test
    void validSignedWebhook_isAcceptedAndConfirmsRecovery() throws Exception {
        Transaction transaction = transactionWithSuccessfulAttempt("plink_http_valid", new BigDecimal("1500.00"));
        String payload = payload("plink_http_valid", "pay_http_valid", 150000L, "INR");
        String signature = RazorpayWebhookSignature.sign(payload, SECRET);

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", signature)
                        .header("X-Razorpay-Event-Id", "evt_http_valid")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Transaction reloaded = transactionRepository.findById(transaction.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.RECOVERED);
    }

    @Test
    void invalidSignature_returns400_andNeverConfirmsAnything() throws Exception {
        Transaction transaction = transactionWithSuccessfulAttempt("plink_http_invalid", new BigDecimal("1500.00"));
        String payload = payload("plink_http_invalid", "pay_x", 150000L, "INR");

        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "clearly-not-a-valid-signature")
                        .content(payload))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(transactionRepository.findById(transaction.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void missingSignatureHeader_returns400() throws Exception {
        String payload = payload("plink_http_missing", "pay_x", 100000L, "INR");
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidSignatureResponse_neverLeaksSecretOrPayload() throws Exception {
        String payload = payload("plink_x", "pay_x", 100000L, "INR");
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "bad-signature")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(SECRET);
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("plink_x");
                });
    }
}
