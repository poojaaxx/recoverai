package com.recoverai.domain;

/**
 * Generic payment method categories used by the synthetic dataset and the
 * transaction model. These are application-level categories, not literal
 * Razorpay payment method identifiers.
 */
public enum PaymentMethod {
    CARD,
    UPI,
    NETBANKING,
    WALLET,
    EMI
}
