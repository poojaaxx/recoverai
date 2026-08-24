package com.recoverai.domain;

/**
 * Outcome state of a single {@link RecoveryAttempt}.
 * <p>
 * {@code BLOCKED} is distinct from {@code FAILED}: {@code FAILED} means the
 * action was executed (e.g. against Razorpay or the simulation adapter) and
 * did not succeed, while {@code BLOCKED} means the safety policy engine
 * (Phase 4) rejected the action before execution — no external call was
 * made. Keeping them separate is what lets the audit trail distinguish
 * "we tried and it didn't work" from "we refused to try".
 */
public enum RecoveryAttemptStatus {
    PENDING,
    SUCCESS,
    FAILED,
    BLOCKED,
    ESCALATED
}
