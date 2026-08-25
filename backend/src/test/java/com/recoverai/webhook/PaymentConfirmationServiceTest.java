package com.recoverai.webhook;

import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
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

/**
 * Business-logic coverage for {@link PaymentConfirmationService}: signature
 * gating, correlation, amount/currency verification, and the confirmed
 * -state transition - Phase 11 spec §16, items 1-9 and 11-19. Every
 * fixture here is a properly HMAC-signed payload built with {@link
 * RazorpayWebhookSignature#sign}, posted through the real verification
 * path - not a parallel unsigned bypass.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentConfirmationServiceTest {

    private static final String SECRET = "test_webhook_secret";

    @Autowired
    private PaymentConfirmationService service;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private Merchant merchant;
    private Customer customer;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Webhook Test Merchant").email("webhook-" + UUID.randomUUID() + "@example.com").build());
        customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Webhook Customer").email("webhook-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(5).failedPaymentCount(0).build());
    }

    private Transaction transaction(BigDecimal amount, TransactionStatus status) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("webhook_txn_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(amount).currency("INR")
                .status(status).paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name()).attemptCount(1).build());
    }

    /** A recovery attempt shaped like a real, successfully-executed Razorpay payment-link creation - the only kind a webhook can ever legitimately confirm. */
    private RecoveryAttempt successfulAttempt(Transaction transaction, String providerReference) {
        return recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(transaction.getAmount()).provider("razorpay")
                .providerReference(providerReference).executedAt(Instant.now())
                .idempotencyKey(transaction.getId() + ":RETRY_PAYMENT:1").build());
    }

    private static String payload(String eventType, String paymentLinkId, String paymentId, Long amountPaise, String currency) {
        StringBuilder sb = new StringBuilder("{\"event\":\"").append(eventType).append("\",\"payload\":{");
        if (paymentLinkId != null) {
            sb.append("\"payment_link\":{\"entity\":{\"id\":\"").append(paymentLinkId).append("\"}}");
        }
        if (paymentId != null || amountPaise != null || currency != null) {
            if (paymentLinkId != null) sb.append(",");
            sb.append("\"payment\":{\"entity\":{");
            sb.append("\"id\":").append(paymentId == null ? "null" : "\"" + paymentId + "\"").append(",");
            sb.append("\"amount\":").append(amountPaise == null ? "null" : amountPaise).append(",");
            sb.append("\"currency\":").append(currency == null ? "null" : "\"" + currency + "\"");
            sb.append("}}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private WebhookProcessingResult send(String payload, String eventId) throws Exception {
        String signature = RazorpayWebhookSignature.sign(payload, SECRET);
        return service.processRazorpayWebhook(payload, signature, eventId);
    }

    // ---------------------------------------------------------------- 1-3. signature gating

    @Test
    void validSignature_isAccepted() throws Exception {
        Transaction txn = transaction(new BigDecimal("500.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_valid");
        String payload = payload("payment_link.paid", "plink_valid", "pay_valid", 50000L, "INR");

        WebhookProcessingResult result = send(payload, "evt_valid");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.CONFIRMED);
    }

    @Test
    void invalidSignature_isRejected() {
        String payload = payload("payment_link.paid", "plink_x", "pay_x", 10000L, "INR");
        WebhookProcessingResult result = service.processRazorpayWebhook(payload, "not-a-real-signature", "evt_invalid");
        assertThat(result.outcome()).isEqualTo(WebhookOutcome.INVALID_SIGNATURE);
    }

    @Test
    void missingSignature_isRejected() {
        String payload = payload("payment_link.paid", "plink_x", "pay_x", 10000L, "INR");
        WebhookProcessingResult result = service.processRazorpayWebhook(payload, null, "evt_missing");
        assertThat(result.outcome()).isEqualTo(WebhookOutcome.INVALID_SIGNATURE);
    }

    // ---------------------------------------------------------------- 4-5. successful confirmation + amount/currency

    @Test
    void successfulPayment_confirmsRecoveryAndTransaction() throws Exception {
        Transaction txn = transaction(new BigDecimal("2499.00"), TransactionStatus.FAILED);
        RecoveryAttempt attempt = successfulAttempt(txn, "plink_confirm");
        String payload = payload("payment_link.paid", "plink_confirm", "pay_confirm", 249900L, "INR");

        WebhookProcessingResult result = send(payload, "evt_confirm");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(result.recoveryAttemptId()).isEqualTo(attempt.getId());

        RecoveryAttempt reloaded = recoveryAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getPaymentConfirmationStatus()).isEqualTo(PaymentConfirmationStatus.CONFIRMED);
        assertThat(reloaded.getConfirmedAmount()).isEqualByComparingTo("2499.00");
        assertThat(reloaded.getConfirmedCurrency()).isEqualTo("INR");
        assertThat(reloaded.getProviderPaymentId()).isEqualTo("pay_confirm");
        assertThat(reloaded.getConfirmedAt()).isNotNull();
        assertThat(reloaded.getAmountRecovered()).isEqualByComparingTo("2499.00");

        Transaction reloadedTxn = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloadedTxn.getStatus()).isEqualTo(TransactionStatus.RECOVERED);
    }

    @Test
    void amountMismatch_isRejectedAndTransactionUnchanged() throws Exception {
        Transaction txn = transaction(new BigDecimal("2499.00"), TransactionStatus.FAILED);
        RecoveryAttempt attempt = successfulAttempt(txn, "plink_amount_mismatch");
        String payload = payload("payment_link.paid", "plink_amount_mismatch", "pay_x", 100000L, "INR");

        WebhookProcessingResult result = send(payload, "evt_amount_mismatch");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.REJECTED);
        assertThat(result.reason()).contains("Amount mismatch");

        RecoveryAttempt reloaded = recoveryAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getPaymentConfirmationStatus()).isEqualTo(PaymentConfirmationStatus.REJECTED);
        assertThat(reloaded.getAmountRecovered()).isNull();

        Transaction reloadedTxn = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloadedTxn.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void currencyMismatch_isRejectedAndTransactionUnchanged() throws Exception {
        Transaction txn = transaction(new BigDecimal("500.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_currency_mismatch");
        String payload = payload("payment_link.paid", "plink_currency_mismatch", "pay_x", 50000L, "USD");

        WebhookProcessingResult result = send(payload, "evt_currency_mismatch");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.REJECTED);
        assertThat(result.reason()).contains("Currency mismatch");
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    // ---------------------------------------------------------------- 7-8. unknown / unmatched

    @Test
    void unknownPaymentLink_isRejectedSafely() throws Exception {
        String payload = payload("payment_link.paid", "plink_does_not_exist", "pay_x", 10000L, "INR");
        WebhookProcessingResult result = send(payload, "evt_unknown_link");
        assertThat(result.outcome()).isEqualTo(WebhookOutcome.REJECTED);
        assertThat(result.recoveryAttemptId()).isNull();
    }

    @Test
    void missingProviderIdentifier_isRejectedSafely() throws Exception {
        String payload = payload("payment_link.paid", null, "pay_x", 10000L, "INR");
        WebhookProcessingResult result = send(payload, "evt_missing_link");
        assertThat(result.outcome()).isEqualTo(WebhookOutcome.REJECTED);
    }

    // ---------------------------------------------------------------- 9. duplicate (sequential)

    @Test
    void duplicateWebhookEvent_doesNotDoubleConfirm() throws Exception {
        Transaction txn = transaction(new BigDecimal("750.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_dup");
        String payload = payload("payment_link.paid", "plink_dup", "pay_dup", 75000L, "INR");

        WebhookProcessingResult first = send(payload, "evt_dup");
        WebhookProcessingResult second = send(payload, "evt_dup");

        assertThat(first.outcome()).isEqualTo(WebhookOutcome.CONFIRMED);
        assertThat(second.outcome()).isEqualTo(WebhookOutcome.ALREADY_PROCESSED);

        RecoveryAttempt reloaded = recoveryAttemptRepository.findByProviderReference("plink_dup").orElseThrow();
        assertThat(reloaded.getAmountRecovered()).isEqualByComparingTo("750.00");
    }

    @Test
    void reconfirmingAnAlreadyConfirmedAttempt_isTreatedAsDuplicate() throws Exception {
        Transaction txn = transaction(new BigDecimal("300.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_reconfirm");
        String payload = payload("payment_link.paid", "plink_reconfirm", "pay_reconfirm", 30000L, "INR");

        send(payload, "evt_reconfirm_1");
        // A different event id, same underlying payment link - simulates Razorpay firing both
        // payment_link.paid and a later payment.captured-shaped event for the same payment.
        WebhookProcessingResult secondEvent = send(payload, "evt_reconfirm_2");

        assertThat(secondEvent.outcome()).isEqualTo(WebhookOutcome.ALREADY_PROCESSED);
    }

    // ---------------------------------------------------------------- 15-16. STOPPED / BLOCKED cannot be confirmed

    @Test
    void attemptWithoutSuccessfulExecution_cannotBeConfirmed() throws Exception {
        Transaction txn = transaction(new BigDecimal("500.00"), TransactionStatus.STOPPED);
        RecoveryAttempt blockedAttempt = recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.BLOCKED)
                .attemptNumber(1).amount(txn.getAmount()).executedAt(Instant.now())
                .idempotencyKey(txn.getId() + ":RETRY_PAYMENT:1-blocked").build());
        // A BLOCKED attempt never calls the gateway (no providerReference) - simulate an attacker
        // or a misconfigured test still trying to correlate a webhook to it directly by forcing one.
        blockedAttempt.setProviderReference("plink_blocked_attempt");
        recoveryAttemptRepository.save(blockedAttempt);

        String payload = payload("payment_link.paid", "plink_blocked_attempt", "pay_x", 50000L, "INR");
        WebhookProcessingResult result = send(payload, "evt_blocked");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.REJECTED);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.STOPPED);
    }

    // ---------------------------------------------------------------- 17. execution success without webhook stays unconfirmed

    @Test
    void executionSuccessWithoutWebhook_remainsNotConfirmed() {
        Transaction txn = transaction(new BigDecimal("500.00"), TransactionStatus.FAILED);
        RecoveryAttempt attempt = successfulAttempt(txn, "plink_never_confirmed");

        assertThat(attempt.getPaymentConfirmationStatus()).isEqualTo(PaymentConfirmationStatus.NOT_CONFIRMED);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    // ---------------------------------------------------------------- 18. audit events

    @Test
    void confirmedPayment_writesAuditTrail() throws Exception {
        Transaction txn = transaction(new BigDecimal("999.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_audit");
        String payload = payload("payment_link.paid", "plink_audit", "pay_audit", 99900L, "INR");

        send(payload, "evt_audit");

        List<String> eventTypes = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId())
                .stream().map(a -> a.getEventType()).toList();
        assertThat(eventTypes).contains("PAYMENT_WEBHOOK_RECEIVED", "PAYMENT_CONFIRMATION_VERIFIED", "PAYMENT_RECOVERY_CONFIRMED");
    }

    // ---------------------------------------------------------------- 19. no secrets anywhere

    @Test
    void noResultOrAuditEverContainsTheWebhookSecret() throws Exception {
        Transaction txn = transaction(new BigDecimal("120.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_secret_check");
        String payload = payload("payment_link.paid", "plink_secret_check", "pay_x", 12000L, "INR");

        WebhookProcessingResult result = send(payload, "evt_secret_check");

        assertThat(result.reason()).doesNotContain(SECRET);
        List<com.recoverai.domain.AuditLog> logs = auditLogRepository.findByTransactionIdOrderByTimestampAsc(txn.getId());
        for (var log : logs) {
            assertThat(String.valueOf(log.getMetadata())).doesNotContain(SECRET);
            assertThat(String.valueOf(log.getReason())).doesNotContain(SECRET);
        }
    }

    // ---------------------------------------------------------------- unsupported event type

    @Test
    void unsupportedEventType_isIgnoredAndNeverConfirmsAnything() throws Exception {
        Transaction txn = transaction(new BigDecimal("400.00"), TransactionStatus.FAILED);
        successfulAttempt(txn, "plink_ignored");
        String payload = payload("payment_link.cancelled", "plink_ignored", null, null, null);

        WebhookProcessingResult result = send(payload, "evt_ignored");

        assertThat(result.outcome()).isEqualTo(WebhookOutcome.IGNORED);
        assertThat(transactionRepository.findById(txn.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
    }
}
