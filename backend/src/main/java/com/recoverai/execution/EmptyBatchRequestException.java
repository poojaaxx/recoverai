package com.recoverai.execution;

/** Thrown by {@code BatchRecoveryExecutionService} when a batch execution request contains no transaction ids at all. */
public class EmptyBatchRequestException extends RuntimeException {
    public EmptyBatchRequestException() {
        super("Batch execution request must name at least one transaction id.");
    }
}
