package com.recoverai.execution;

import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.config.RecoveryPolicyProperties;
import com.recoverai.domain.AuditLog;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.Transaction;
import com.recoverai.dto.BatchExecutionItemResult;
import com.recoverai.dto.BatchExecutionOutcome;
import com.recoverai.dto.BatchExecutionResponse;
import com.recoverai.dto.RecoveryAgentEvaluationResponse;
import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded batch recovery execution (Phase 14, section 2/5) - the only
 * multi-transaction execution endpoint in the system. Deliberately not an
 * unrestricted "execute everything": every id is bounded by {@link
 * RecoveryPolicyProperties#getMaxBatchTransactionCount()}, reloaded fresh
 * from the database, and re-run through the exact same AI + policy +
 * execution pipeline a single-transaction {@code POST
 * /api/recovery/{id}/execute} uses ({@link RecoveryExecutionService#execute}
 * - no parallel/shortcut execution path exists here).
 * <p>
 * <b>How the aggregate ceiling is enforced without ever partially exceeding
 * it:</b> before calling {@code execute()} for a transaction the fresh
 * policy preview ({@link RecoveryAgentService#evaluatePreview}) already
 * says would be {@code ALLOW}, this service checks whether that
 * transaction's amount would push the running total past {@link
 * RecoveryPolicyProperties#getMaxBatchAggregateAmount()}; if so, the
 * transaction is skipped (never executed) rather than executed and the
 * ceiling exceeded. {@code execute()} itself re-runs the entire pipeline
 * fresh immediately afterward, so nothing here ever executes on a stale
 * decision.
 * <p>
 * <b>Duplicate ids and idempotency:</b> the request's transaction ids are
 * de-duplicated before processing, so a transaction can never be executed
 * twice within one batch call; {@code RecoveryExecutionService}'s own
 * idempotency-key mechanism additionally protects against a transaction
 * that was already executed by a prior request entirely.
 * <p>
 * <b>Isolation between items:</b> each transaction is processed and
 * persisted independently (via {@code RecoveryExecutionService}'s own
 * per-transaction {@code TransactionTemplate} boundary) - one transaction
 * throwing an unexpected exception is caught and reported as its own
 * failed item, and never aborts or rolls back any other transaction's
 * already-committed result.
 */
@Service
public class BatchRecoveryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BatchRecoveryExecutionService.class);
    /** Kept within audit_logs.actor's VARCHAR(30) column limit (migration V6). */
    private static final String ACTOR = "BATCH_RECOVERY_SERVICE";

    private final TransactionRepository transactionRepository;
    private final RecoveryAgentService recoveryAgentService;
    private final RecoveryExecutionService recoveryExecutionService;
    private final RecoveryPolicyProperties policyProperties;
    private final AuditLogRepository auditLogRepository;

    public BatchRecoveryExecutionService(TransactionRepository transactionRepository,
                                          RecoveryAgentService recoveryAgentService,
                                          RecoveryExecutionService recoveryExecutionService,
                                          RecoveryPolicyProperties policyProperties,
                                          AuditLogRepository auditLogRepository) {
        this.transactionRepository = transactionRepository;
        this.recoveryAgentService = recoveryAgentService;
        this.recoveryExecutionService = recoveryExecutionService;
        this.policyProperties = policyProperties;
        this.auditLogRepository = auditLogRepository;
    }

    public BatchExecutionResponse executeBatch(List<UUID> requestedIds, String actor) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            throw new EmptyBatchRequestException();
        }

        List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(requestedIds));
        int duplicateRequestCount = requestedIds.size() - distinct.size();

        int maxBatchSize = policyProperties.getMaxBatchTransactionCount();
        if (distinct.size() > maxBatchSize) {
            throw new BatchSizeExceededException(distinct.size(), maxBatchSize);
        }

        BigDecimal maxAggregate = policyProperties.getMaxBatchAggregateAmount();
        BigDecimal aggregateExecuted = zero();

        List<BatchExecutionItemResult> results = new ArrayList<>();
        int executedCount = 0, failedProviderCallCount = 0, alreadyExecutedCount = 0,
                blockedCount = 0, escalatedCount = 0, stoppedCount = 0,
                skippedPortfolioLimitCount = 0, notFoundCount = 0;

        for (UUID id : distinct) {
            Optional<Transaction> maybeTransaction = transactionRepository.findById(id);
            if (maybeTransaction.isEmpty()) {
                notFoundCount++;
                results.add(new BatchExecutionItemResult(id, null, BatchExecutionOutcome.NOT_FOUND,
                        null, null, null, null, "No transaction exists with this id."));
                continue;
            }
            Transaction transaction = maybeTransaction.get();

            // The whole per-transaction body is wrapped in one try/catch: a genuinely
            // unexpected failure anywhere here (e.g. a concurrent delete/reseed racing
            // this exact transaction between the lookup above and a later step - a real
            // production incident this handling was added for) must never surface a raw
            // exception message (SQL, stack trace, internal schema) in the API response,
            // and must never abort the rest of the batch - each transaction is isolated.
            try {
                RecoveryAgentEvaluationResponse preview = recoveryAgentService.evaluatePreview(id);
                PolicyDecision previewDecision = preview.policyDecision().decision();

                if (previewDecision != PolicyDecision.ALLOW) {
                    BatchExecutionOutcome outcome = switch (previewDecision) {
                        case BLOCK -> BatchExecutionOutcome.BLOCKED;
                        case ESCALATE -> BatchExecutionOutcome.ESCALATED;
                        case STOP -> BatchExecutionOutcome.STOPPED;
                        case ALLOW -> throw new IllegalStateException("unreachable");
                    };
                    RecoveryExecutionResponse response = recoveryExecutionService.execute(id);
                    switch (outcome) {
                        case BLOCKED -> blockedCount++;
                        case ESCALATED -> escalatedCount++;
                        case STOPPED -> stoppedCount++;
                        default -> { }
                    }
                    results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(), outcome,
                            previewDecision, response.action(), response.recoveryAttemptId(),
                            transaction.getAmount(), response.policyDecision() == null ? null : response.policyDecision().reason()));
                    continue;
                }

                // previewDecision == ALLOW: enforce the portfolio-wide aggregate ceiling
                // BEFORE ever calling execute(), so it is never partially exceeded.
                BigDecimal amount = transaction.getAmount();
                if (aggregateExecuted.add(amount).compareTo(maxAggregate) > 0) {
                    skippedPortfolioLimitCount++;
                    String reason = "Batch aggregate recovery limit (%s) would be exceeded by including this transaction (%s); skipped without executing."
                            .formatted(maxAggregate, amount);
                    writeSkipAudit(transaction, reason);
                    results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(),
                            BatchExecutionOutcome.SKIPPED_PORTFOLIO_LIMIT, previewDecision, preview.finalAction(),
                            null, amount, reason));
                    continue;
                }

                RecoveryExecutionResponse response = recoveryExecutionService.execute(id);
                if (response.duplicate()) {
                    alreadyExecutedCount++;
                    results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(),
                            BatchExecutionOutcome.ALREADY_EXECUTED, previewDecision, response.action(),
                            response.recoveryAttemptId(), amount, response.executionNote()));
                    continue;
                }
                if (response.failureCode() != null) {
                    // A gateway call was attempted and the provider reported failure -
                    // response.executed() is false in this case (see RecoveryExecutionResponse
                    // javadoc: executed() reflects PaymentExecutionResult.success()), so this
                    // must be checked before the executed()/executionStatus() branch below.
                    failedProviderCallCount++;
                    results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(),
                            BatchExecutionOutcome.FAILED_PROVIDER_CALL, previewDecision, response.action(),
                            response.recoveryAttemptId(), amount, response.failureReason()));
                    continue;
                }
                if (response.executed() || response.executionStatus() != null) {
                    // executed() covers a real successful gateway call; executionStatus()
                    // non-null with executed()==false covers a recorded non-payment action
                    // (e.g. a reminder) - both are genuine batch executions.
                    executedCount++;
                    aggregateExecuted = aggregateExecuted.add(amount);
                    results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(),
                            BatchExecutionOutcome.EXECUTED, previewDecision, response.action(),
                            response.recoveryAttemptId(), amount, response.executionNote()));
                    continue;
                }

                // Fresh execute() re-evaluated policy and it was no longer ALLOW (e.g. a
                // concurrent action changed state between preview and execute) - report
                // whatever the fresh, authoritative decision actually was.
                PolicyDecision freshDecision = response.policyDecision() == null ? previewDecision : response.policyDecision().decision();
                BatchExecutionOutcome outcome = switch (freshDecision) {
                    case BLOCK -> BatchExecutionOutcome.BLOCKED;
                    case ESCALATE -> BatchExecutionOutcome.ESCALATED;
                    case STOP -> BatchExecutionOutcome.STOPPED;
                    case ALLOW -> BatchExecutionOutcome.FAILED_PROVIDER_CALL;
                };
                switch (outcome) {
                    case BLOCKED -> blockedCount++;
                    case ESCALATED -> escalatedCount++;
                    case STOPPED -> stoppedCount++;
                    default -> failedProviderCallCount++;
                }
                results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(), outcome,
                        freshDecision, response.action(), response.recoveryAttemptId(), amount, response.executionNote()));
            } catch (Exception e) {
                // Never surface e.getMessage() - see this method's javadoc for the real
                // incident (a raw SQL/foreign-key-violation message reaching the API
                // response) that made this the required behavior, not a hypothetical.
                log.warn("Batch processing failed unexpectedly for transaction {}: {}", id, e.toString());
                results.add(new BatchExecutionItemResult(id, transaction.getExternalTransactionId(),
                        BatchExecutionOutcome.FAILED_PROVIDER_CALL, null, null, null,
                        transaction.getAmount(), "This transaction could not be processed; nothing executed for it. The rest of the batch continued."));
                failedProviderCallCount++;
            }
        }

        log.info("Batch recovery execution by {}: requested={} distinct={} executed={} blocked={} escalated={} stopped={} skippedPortfolioLimit={} notFound={} aggregateExecuted={}",
                actor, requestedIds.size(), distinct.size(), executedCount, blockedCount, escalatedCount,
                stoppedCount, skippedPortfolioLimitCount, notFoundCount, aggregateExecuted);

        return new BatchExecutionResponse(requestedIds.size(), distinct.size(), duplicateRequestCount,
                executedCount, failedProviderCallCount, alreadyExecutedCount, blockedCount, escalatedCount,
                stoppedCount, skippedPortfolioLimitCount, notFoundCount, aggregateExecuted, maxAggregate,
                maxBatchSize, results);
    }

    private void writeSkipAudit(Transaction transaction, String reason) {
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction)
                .eventType("RECOVERY_BATCH_SKIPPED_PORTFOLIO_LIMIT")
                .actor(ACTOR)
                .reason(reason)
                .timestamp(Instant.now())
                .build());
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}
