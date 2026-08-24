package com.recoverai.payment;

import com.recoverai.domain.AuditLog;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentAuditEventsTest {

    private final Transaction transaction = Transaction.builder()
            .id(UUID.randomUUID())
            .externalTransactionId("txn_audit_test")
            .build();

    @Test
    void successfulResult_producesSuccessAuditShape_amountRecoveredZero() {
        PaymentExecutionResult result = new PaymentExecutionResult(
                true, "mock", "mock_ref_123", transaction.getId(), RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("2499.00"), "INR", BigDecimal.ZERO, true, "created",
                null, null, "idem-key", Instant.now());

        AuditLog audit = PaymentAuditEvents.forResult(transaction, result);

        assertThat(audit.getEventType()).isEqualTo("PAYMENT_PROVIDER_EXECUTION");
        assertThat(audit.getActor()).isEqualTo("PAYMENT_GATEWAY");
        assertThat(audit.getDecision()).isEqualTo("SUCCESS");
        assertThat(audit.getMetadata()).containsEntry("provider", "mock");
        assertThat(audit.getMetadata()).containsEntry("simulated", true);
        assertThat(audit.getMetadata().get("amountRecovered")).isEqualTo(BigDecimal.ZERO);
        assertThat(audit.getReason()).contains("no confirmed recovery yet");
    }

    @Test
    void failedResult_producesFailureAuditShape() {
        PaymentExecutionResult result = new PaymentExecutionResult(
                false, "razorpay", null, transaction.getId(), RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("2499.00"), "INR", BigDecimal.ZERO, false, "failed",
                PaymentFailureReason.DECLINED, "Card declined by issuer", "idem-key", Instant.now());

        AuditLog audit = PaymentAuditEvents.forResult(transaction, result);

        assertThat(audit.getDecision()).isEqualTo("FAILED");
        assertThat(audit.getReason()).isEqualTo("Card declined by issuer");
        assertThat(audit.getMetadata()).containsEntry("failureCode", "DECLINED");
    }

    @Test
    void metadata_neverContainsSecretLikeFields() {
        PaymentExecutionResult result = new PaymentExecutionResult(
                true, "razorpay", "plink_abc", transaction.getId(), RecoveryAction.CREATE_PAYMENT_LINK,
                new BigDecimal("500.00"), "INR", BigDecimal.ZERO, false, "created",
                null, null, "idem-key", Instant.now());

        AuditLog audit = PaymentAuditEvents.forResult(transaction, result);

        assertThat(audit.getMetadata()).doesNotContainKeys("apiKey", "keySecret", "authorization", "Authorization");
    }
}
