package com.recoverai.payment;

/**
 * Structured categorization of why a {@link PaymentGateway} operation did
 * not succeed - never left as a raw provider error string, and never
 * silently treated as success. {@code null} on a successful {@link
 * PaymentExecutionResult}.
 */
public enum PaymentFailureReason {
    AUTHENTICATION_FAILURE,
    INVALID_REQUEST,
    DECLINED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    RATE_LIMITED,
    MALFORMED_RESPONSE,
    AMOUNT_MISMATCH,
    TRANSACTION_MISMATCH,
    UNKNOWN
}
