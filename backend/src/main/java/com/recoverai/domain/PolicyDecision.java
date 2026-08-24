package com.recoverai.domain;

/**
 * The outcome of a {@code RecoveryPolicyService} evaluation - never
 * produced by an AI/LLM, always a deterministic function of the
 * transaction's authoritative database state.
 * <p>
 * {@code ALLOW} - the proposed action satisfies every configured safety
 * constraint. {@code BLOCK} - the proposed action is invalid or explicitly
 * prohibited for this transaction's state (e.g. it is already resolved, or
 * this exact action was already performed). {@code ESCALATE} - the action
 * requires human review before it can proceed (e.g. amount exceeds the
 * autonomous limit, or the transaction is already under manual review).
 * {@code STOP} - the transaction has reached a stopping condition and
 * autonomous recovery must cease for it entirely.
 */
public enum PolicyDecision {
    ALLOW,
    BLOCK,
    ESCALATE,
    STOP
}
