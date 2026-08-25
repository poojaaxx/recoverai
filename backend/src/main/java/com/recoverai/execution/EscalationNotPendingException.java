package com.recoverai.execution;

import com.recoverai.domain.TransactionStatus;

import java.util.UUID;

/** Thrown by {@code RecoveryExecutionService.approveEscalation}/{@code rejectEscalation} when the transaction isn't currently in ESCALATED status - nothing pending for a human to approve or reject. */
public class EscalationNotPendingException extends RuntimeException {
    public EscalationNotPendingException(UUID transactionId, TransactionStatus currentStatus) {
        super("Transaction %s is not pending escalation approval (current status: %s).".formatted(transactionId, currentStatus));
    }
}
