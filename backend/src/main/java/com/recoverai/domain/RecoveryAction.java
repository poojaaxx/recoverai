package com.recoverai.domain;

/**
 * The bounded set of actions the recovery system is allowed to take on a
 * transaction. The AI agent (Phase 5) may only recommend one of these; the
 * safety policy engine (Phase 4) decides whether it is actually allowed to
 * execute.
 */
public enum RecoveryAction {
    RETRY_PAYMENT,
    CREATE_PAYMENT_LINK,
    SEND_RECOVERY_REMINDER,
    ESCALATE,
    STOP
}
