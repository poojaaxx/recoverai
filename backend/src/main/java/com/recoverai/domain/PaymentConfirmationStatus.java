package com.recoverai.domain;

/**
 * Whether a {@link RecoveryAttempt}'s provider operation has been confirmed
 * as an actual customer payment - a strictly separate fact from {@link
 * RecoveryAttemptStatus#SUCCESS}, which only means the provider call itself
 * (e.g. creating a payment link) went through. Only {@code CONFIRMED} means
 * money was genuinely recovered; see {@code
 * com.recoverai.webhook.PaymentConfirmationService}.
 */
public enum PaymentConfirmationStatus {
    /** No verified provider confirmation has been received yet (the default for every attempt). */
    NOT_CONFIRMED,
    /** A verified, signature-checked provider webhook confirmed this exact attempt for the expected amount/currency. */
    CONFIRMED,
    /** A verified webhook arrived but could not be trusted as confirmation of this attempt (amount/currency mismatch, or no match). */
    REJECTED
}
