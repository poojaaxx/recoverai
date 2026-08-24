package com.recoverai.payment;

import com.recoverai.domain.RecoveryAction;

import java.util.UUID;

/**
 * Deterministic idempotency key generation for payment-gateway operations.
 * A key is derived from {@code transactionId + action + attemptNumber}
 * (never {@code transactionId} alone - the same transaction can
 * legitimately have several distinct recovery attempts) and is enforced
 * with a real database uniqueness constraint on {@code
 * recovery_attempts.idempotency_key} (migration V9), so the same recovery
 * attempt can never accidentally produce two provider executions.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    public static String forAttempt(UUID transactionId, RecoveryAction action, int attemptNumber) {
        return transactionId + ":" + action.name() + ":" + attemptNumber;
    }
}
