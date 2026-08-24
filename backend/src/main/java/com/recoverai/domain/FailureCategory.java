package com.recoverai.domain;

/**
 * Synthetic, application-level failure categories used to classify why a
 * {@link Transaction} failed. These are NOT official Razorpay failure
 * codes — they are a simplified taxonomy invented for this project's
 * synthetic dataset and demo scenarios. {@link Transaction#getFailureCode()}
 * stores {@link #name()} as a plain string so real gateway failure codes
 * (which this application does not control) can be stored the same way in
 * future phases without a schema change.
 */
public enum FailureCategory {
    TEMPORARY_FAILURE,
    INSUFFICIENT_FUNDS,
    BANK_DECLINED,
    NETWORK_ERROR,
    AUTHENTICATION_FAILURE,
    LIMIT_EXCEEDED,
    UNKNOWN
}
