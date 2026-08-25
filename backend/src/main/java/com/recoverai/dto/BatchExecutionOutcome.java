package com.recoverai.dto;

/**
 * Per-transaction disposition within a bounded batch execution ({@code
 * BatchRecoveryExecutionService}). Distinct from the underlying {@code
 * PolicyDecision}/{@code RecoveryAttemptStatus} enums because a batch item
 * can additionally be skipped for reasons that have nothing to do with
 * that transaction's own policy check - a portfolio-wide ceiling, or the
 * id simply not existing.
 */
public enum BatchExecutionOutcome {
    /** Policy allowed it, a provider call was attempted, and it succeeded (or is a recorded non-payment action). */
    EXECUTED,
    /** Policy allowed it and a provider call was attempted, but the provider reported failure. */
    FAILED_PROVIDER_CALL,
    /** This exact action for this transaction was already executed (idempotent replay) - nothing new happened. */
    ALREADY_EXECUTED,
    /** Fresh policy re-evaluation returned BLOCK. */
    BLOCKED,
    /** Fresh policy re-evaluation returned ESCALATE; the transaction's status is durably set to ESCALATED. */
    ESCALATED,
    /** Fresh policy re-evaluation returned STOP; the transaction's status is durably set to STOPPED. */
    STOPPED,
    /** Policy would have allowed it, but executing it would have exceeded the batch's aggregate monetary ceiling - skipped without calling the provider. */
    SKIPPED_PORTFOLIO_LIMIT,
    /** The transaction id in the request does not exist. */
    NOT_FOUND
}
