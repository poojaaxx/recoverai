package com.recoverai.domain;

/**
 * Coarse-grained category of an {@link RecoveryAction} recommendation,
 * used by the AI recovery agent (Phase 5) to explain the *shape* of its
 * recommendation independent of the exact action - e.g. both {@code
 * CREATE_PAYMENT_LINK} and {@code SEND_RECOVERY_REMINDER} are a {@code
 * REENGAGE} intervention.
 */
public enum InterventionType {
    RETRY,
    REENGAGE,
    ESCALATE,
    STOP
}
