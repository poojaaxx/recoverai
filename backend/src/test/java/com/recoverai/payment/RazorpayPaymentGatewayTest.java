package com.recoverai.payment;

import com.recoverai.config.RazorpayProperties;
import com.recoverai.domain.RecoveryAction;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RazorpayPaymentGateway} tests that do not require network access
 * or real credentials - request validation (shared with {@link
 * MockPaymentGateway}) and the missing-credentials fail-closed path. The
 * actual HTTP call to Razorpay's API is intentionally not exercised here
 * (no Test Mode credentials are available in this environment) - see the
 * Phase 6 report's "Known limitations".
 */
class RazorpayPaymentGatewayTest {

    private RazorpayPaymentGateway gatewayWithoutCredentials() {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setEnabled(true);
        properties.setMode("test");
        // keyId/keySecret left blank
        return new RazorpayPaymentGateway(properties, WebClient.builder().build());
    }

    private PaymentExecutionRequest request(RecoveryAction action, BigDecimal amount, String currency) {
        UUID transactionId = UUID.randomUUID();
        return new PaymentExecutionRequest(transactionId, "txn_test", action, amount, currency,
                IdempotencyKeys.forAttempt(transactionId, action, 1));
    }

    @Test
    void missingCredentials_failsClosed_asStructuredResult_notException() {
        RazorpayPaymentGateway gateway = gatewayWithoutCredentials();

        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, new BigDecimal("100"), "INR"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.AUTHENTICATION_FAILURE);
        assertThat(result.simulated()).isFalse();
    }

    @Test
    void invalidRequest_isRejectedBeforeAnyNetworkCall_evenWithoutCredentials() {
        RazorpayPaymentGateway gateway = gatewayWithoutCredentials();

        PaymentExecutionResult zeroAmount = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, BigDecimal.ZERO, "INR"));
        assertThat(zeroAmount.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);

        PaymentExecutionResult badCurrency = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, new BigDecimal("100"), "USD"));
        assertThat(badCurrency.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);

        PaymentExecutionResult unsupportedAction = gateway.execute(request(RecoveryAction.STOP, new BigDecimal("100"), "INR"));
        assertThat(unsupportedAction.failureCode()).isEqualTo(PaymentFailureReason.INVALID_REQUEST);
    }

    @Test
    void failureResults_neverExposeCredentials() {
        RazorpayProperties properties = new RazorpayProperties();
        properties.setEnabled(true);
        properties.setMode("test");
        properties.setKeyId("rzp_test_should_not_leak");
        properties.setKeySecret("super_secret_value_should_not_leak");
        // Reserved, guaranteed-unused local port - fails fast with "connection refused",
        // exercising the real catch-all failure path with zero external network access
        // (this test must never make a real call - see Phase 6 spec section 23).
        properties.setBaseUrl("http://127.0.0.1:1");
        RazorpayPaymentGateway gateway = new RazorpayPaymentGateway(properties, WebClient.builder().build());

        PaymentExecutionResult result = gateway.execute(request(RecoveryAction.RETRY_PAYMENT, new BigDecimal("100"), "INR"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(PaymentFailureReason.PROVIDER_UNAVAILABLE);
        assertThat(result.failureReason()).doesNotContain("super_secret_value_should_not_leak");
        assertThat(result.toString()).doesNotContain("super_secret_value_should_not_leak");
    }
}
