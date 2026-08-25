package com.recoverai.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result of {@code POST /api/recovery/batch/execute}. Deliberately no
 * single aggregate "success" flag - a batch is a set of independent
 * per-transaction outcomes (see {@link BatchExecutionItemResult}), and
 * hiding that behind one boolean would let a judge or caller miss a
 * partial failure.
 * <p>
 * <b>Execution success is still not confirmed revenue.</b> {@code
 * executedCount} counts provider calls that were attempted and succeeded
 * (or recorded non-payment actions); it is not {@code
 * confirmedRecoveryCount} and {@code aggregateAmountExecuted} is not
 * confirmed recovered revenue - only a subsequent signed webhook
 * confirmation (see {@code PaymentConfirmationService}) can report that,
 * exactly as for a single-transaction execution.
 */
public record BatchExecutionResponse(
        int totalRequested,
        int distinctCount,
        int duplicateRequestCount,
        int executedCount,
        int failedProviderCallCount,
        int alreadyExecutedCount,
        int blockedCount,
        int escalatedCount,
        int stoppedCount,
        int skippedPortfolioLimitCount,
        int notFoundCount,
        BigDecimal aggregateAmountExecuted,
        BigDecimal maxAggregateAmount,
        int maxTransactionCount,
        List<BatchExecutionItemResult> results
) {
}
