package com.recoverai.payment;

import com.recoverai.domain.RecoveryAction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain unit tests - {@link MockPaymentGateway} has no dependencies, so no Spring context is needed. */
class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    private PaymentExecutionRequest request(RecoveryAction action, BigDecimal amount, String currency, String externalId) {
        UUID transactionId = UUID.randomUUID();
        return new PaymentExecutionRequest(transactionId, externalId, action, amount, currency,
                IdempotencyKeys.forAttempt(transactionId, action, 1));
    }

    // ---------------------------------------------------------------- 1. success

    @Test
    void wellFormedRetryPayment_succeeds() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("2499.00"), "INR", "txn_ok"));

        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.simulated()).isTrue();
        assertThat(result.providerReference()).isNotBlank();
        assertThat(result.failureCode()).isNull();
    }

    @Test
    void wellFormedCreatePaymentLink_succeeds() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.CREATE_PAYMENT_LINK,
                new BigDecimal("2499.00"), "INR", "txn_ok"));

        assertThat(result.success()).isTrue();
        assertThat(result.simulated()).isTrue();
    }

    // ---------------------------------------------------------------- 2. failure (deterministic markers)

    @Test
    void mockDeclinePrefix_simulatesDecline() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("2499.00"), "INR", "mock-decline-anything"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.DECLINED);
        assertThat(result.simulated()).isTrue();
    }

    @Test
    void mockTimeoutPrefix_simulatesTimeout() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("2499.00"), "INR", "mock-timeout-anything"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.TIMEOUT);
    }

    // ---------------------------------------------------------------- 3. deterministic behavior

    @Test
    void sameRequest_producesIdenticalResultShape_everyTime() {
        PaymentExecutionRequest req = request(RecoveryAction.RETRY_PAYMENT, new BigDecimal("999.00"), "INR", "txn_det");

        PaymentExecutionResult first = gateway.execute(req);
        PaymentExecutionResult second = gateway.execute(req);

        assertThat(second.success()).isEqualTo(first.success());
        assertThat(second.providerReference()).isEqualTo(first.providerReference());
        assertThat(second.amountRecovered()).isEqualByComparingTo(first.amountRecovered());
    }

    // ---------------------------------------------------------------- validation (shared logic, sections 18/19/23)

    @Test
    void zeroAmount_isRejected() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, BigDecimal.ZERO, "INR", "txn"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);
    }

    @Test
    void negativeAmount_isRejected() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, new BigDecimal("-100"), "INR", "txn"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);
    }

    @Test
    void unsupportedCurrency_isRejected() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, new BigDecimal("100"), "USD", "txn"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);
    }

    @Test
    void unsupportedAction_sendReminder_isRejected() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.SEND_RECOVERY_REMINDER,
                new BigDecimal("100"), "INR", "txn"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);
    }

    @Test
    void unsupportedAction_escalate_isRejected() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.ESCALATE, new BigDecimal("100"), "INR", "txn"));
        assertThat(result.success()).isFalse();
    }

    @Test
    void unsupportedAction_stop_isRejected() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.STOP, new BigDecimal("100"), "INR", "txn"));
        assertThat(result.success()).isFalse();
    }

    // ---------------------------------------------------------------- amountRecovered honesty (section 9)

    @Test
    void successfulExecution_amountRecoveredIsZero_notFullAmount() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("5000.00"), "INR", "txn_ok"));

        assertThat(result.success()).isTrue();
        assertThat(result.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void failedExecution_amountRecoveredIsZero() {
        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT,
                new BigDecimal("5000.00"), "INR", "mock-decline-x"));

        assertThat(result.amountRecovered()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
