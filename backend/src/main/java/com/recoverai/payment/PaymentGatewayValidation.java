package com.recoverai.payment;

import com.recoverai.domain.RecoveryAction;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Request-shape validation shared by every {@link PaymentGateway}
 * implementation - amount/currency/action sanity only. This deliberately
 * does <b>not</b> include any safety/authorization check (retry limits,
 * amount ceilings, duplicate-action windows, risk level) - those remain
 * {@code RecoveryPolicyService}'s responsibility; a gateway only verifies
 * that the request it was asked to execute is well-formed.
 */
final class PaymentGatewayValidation {

    /** Only these two actions represent an actual payment-gateway operation - see {@link PaymentExecutionRequest}. */
    private static final Set<RecoveryAction> GATEWAY_EXECUTABLE_ACTIONS =
            EnumSet.of(RecoveryAction.RETRY_PAYMENT, RecoveryAction.CREATE_PAYMENT_LINK);

    /** Only INR is supported - no currency conversion, documented assumption (see README/docs). */
    private static final String SUPPORTED_CURRENCY = "INR";

    private PaymentGatewayValidation() {
    }

    /** Empty if the request is well-formed; otherwise the failure reason/message to return directly, without calling the provider. */
    static Optional<Failure> validate(PaymentExecutionRequest request) {
        if (!GATEWAY_EXECUTABLE_ACTIONS.contains(request.action())) {
            return Optional.of(new Failure(PaymentFailureReason.INVALID_REQUEST,
                    "%s is not a payment-gateway-executable action; only RETRY_PAYMENT and CREATE_PAYMENT_LINK are."
                            .formatted(request.action())));
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.of(new Failure(PaymentFailureReason.INVALID_REQUEST,
                    "Amount must be a positive value."));
        }
        if (!SUPPORTED_CURRENCY.equals(request.currency())) {
            return Optional.of(new Failure(PaymentFailureReason.INVALID_REQUEST,
                    "Unsupported currency '%s'; only %s is currently supported.".formatted(request.currency(), SUPPORTED_CURRENCY)));
        }
        if (request.transactionId() == null) {
            return Optional.of(new Failure(PaymentFailureReason.INVALID_REQUEST, "transactionId is required."));
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            return Optional.of(new Failure(PaymentFailureReason.INVALID_REQUEST, "idempotencyKey is required."));
        }
        return Optional.empty();
    }

    record Failure(PaymentFailureReason reason, String message) {
    }
}
