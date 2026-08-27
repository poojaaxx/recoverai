package com.recoverai.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Deterministic, offline, no-API-key payment execution - the default
 * {@link PaymentGateway} and the one every automated test runs against.
 * Requires no network access and produces the same result for the same
 * request every time.
 * <p>
 * By default every well-formed request succeeds at the <i>provider call</i>
 * level - matching {@link RazorpayPaymentGateway}'s real semantics,
 * {@code amountRecovered} is always {@link BigDecimal#ZERO} even on
 * success, since creating/sending a payment link is not itself confirmed
 * recovery (see {@link PaymentExecutionResult}'s javadoc). {@code
 * simulated} is always {@code true} so a caller can never mistake a mock
 * result for a real one.
 * <p>
 * To deterministically exercise failure paths in tests (mirroring how
 * real payment sandboxes use magic test values), a request whose {@code
 * externalTransactionId} starts with {@code "mock-decline-"} simulates a
 * provider decline, and one starting with {@code "mock-timeout-"}
 * simulates a provider timeout - documented here as the only two
 * supported conventions, not a general-purpose failure-injection
 * mechanism.
 */
public class MockPaymentGateway implements PaymentGateway {

    static final String PROVIDER_NAME = "mock";
    private static final String DECLINE_PREFIX = "mock-decline-";
    private static final String TIMEOUT_PREFIX = "mock-timeout-";

    @Override
    public PaymentExecutionResult execute(PaymentExecutionRequest request) {
        var invalid = PaymentGatewayValidation.validate(request);
        if (invalid.isPresent()) {
            return failure(request, invalid.get().reason(), invalid.get().message());
        }

        String externalId = request.externalTransactionId() == null
                ? "" : request.externalTransactionId().toLowerCase(Locale.ROOT);
        if (externalId.startsWith(DECLINE_PREFIX)) {
            return failure(request, PaymentFailureReason.DECLINED, "Mock provider simulated a decline for this request.");
        }
        if (externalId.startsWith(TIMEOUT_PREFIX)) {
            return failure(request, PaymentFailureReason.TIMEOUT, "Mock provider simulated a timeout for this request.");
        }

        String providerReference = "mock_" + request.idempotencyKey();
        return new PaymentExecutionResult(
                true, PROVIDER_NAME, providerReference, request.transactionId(), request.action(),
                request.amount(), request.currency(), zero(), true, "created",
                null, null, request.idempotencyKey(), Instant.now(), null);
    }

    private static PaymentExecutionResult failure(PaymentExecutionRequest request, PaymentFailureReason reason, String message) {
        return new PaymentExecutionResult(
                false, PROVIDER_NAME, null, request.transactionId(), request.action(),
                request.amount(), request.currency(), zero(), true, "failed",
                reason, message, request.idempotencyKey(), Instant.now(), null);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
