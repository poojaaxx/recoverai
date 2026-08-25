package com.recoverai.execution;

/** Thrown by {@code BatchRecoveryExecutionService} when a batch request names more distinct transactions than {@code recoverai.policy.max-batch-transaction-count} allows - the whole request is rejected rather than silently truncated. */
public class BatchSizeExceededException extends RuntimeException {
    public BatchSizeExceededException(int requested, int max) {
        super("Batch request named %d distinct transactions, exceeding the maximum of %d allowed per batch.".formatted(requested, max));
    }
}
