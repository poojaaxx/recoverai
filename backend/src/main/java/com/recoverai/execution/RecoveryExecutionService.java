package com.recoverai.execution;

import com.recoverai.agent.RecoveryAgentService;
import com.recoverai.domain.AuditLog;
import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.PolicyDecision;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RecoveryAgentEvaluationResponse;
import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.payment.IdempotencyKeys;
import com.recoverai.payment.PaymentExecutionRequest;
import com.recoverai.payment.PaymentExecutionResult;
import com.recoverai.payment.PaymentGateway;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The bounded Recovery Execution Pipeline (Phase 7) - the first phase
 * that actually connects AI recommendation, policy authorization, and
 * payment execution into one flow:
 * <pre>
 *   Transaction -&gt; RecoveryAgentService.evaluate() -&gt; PolicyDecision
 *              -&gt; ONLY IF ALLOW -&gt; PaymentGateway.execute() -&gt; RecoveryAttempt -&gt; Audit
 * </pre>
 * This service <b>orchestrates</b> existing components - it never
 * re-implements policy logic (that stays entirely inside {@code
 * RecoveryPolicyService}, called transitively via {@code
 * RecoveryAgentService.evaluate()}, which re-runs it fresh on every call,
 * satisfying "policy re-check immediately before execution") and never
 * decides financial safety itself. {@link PaymentGateway} is called only
 * when the fresh policy decision is {@code ALLOW}, only for {@code
 * RETRY_PAYMENT}/{@code CREATE_PAYMENT_LINK}, and only with a
 * server-authoritative {@link PaymentExecutionRequest} built from the
 * persisted {@link Transaction} - never from client input (this service's
 * single public method takes only a transaction id).
 * <p>
 * <b>{@code amountRecovered} is never inflated.</b> Creating/sending a
 * payment link is not confirmation of payment - see {@code
 * PaymentExecutionResult}'s javadoc (Phase 6). This service therefore
 * only transitions a transaction to {@link TransactionStatus#RECOVERED}
 * when {@code result.success() && result.amountRecovered() > 0} - a
 * condition today's {@code PaymentGateway} implementations can never
 * satisfy (both always report {@code amountRecovered=0}), so no
 * transaction is ever marked {@code RECOVERED} by this phase. The mapping
 * is implemented correctly and honestly anyway, for when a future
 * provider-confirmation mechanism (a webhook, Phase 8+) can genuinely
 * report a non-zero confirmed amount.
 * <p>
 * <b>Idempotency and concurrency, using only the existing database.</b> A
 * duplicate/replayed request is detected by a plain {@code SELECT} before
 * ever attempting an insert (cheap, common case). A genuine concurrent
 * race - two threads both passing that check - is resolved by the
 * database's own unique constraint on {@code
 * recovery_attempts.idempotency_key} (migration V9): the losing insert
 * throws {@link DataIntegrityViolationException}, which rolls back that
 * attempt's entire transaction atomically (no partial writes, no
 * duplicate audit rows), and a fresh, separate transaction then resolves
 * the race by returning the winner's now-committed result. No
 * distributed lock, no new infrastructure - just the database's own ACID
 * guarantees.
 */
@Service
public class RecoveryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryExecutionService.class);

    private static final String ACTOR = "RECOVERY_EXECUTION_SERVICE";
    private static final Set<RecoveryAction> GATEWAY_ACTIONS =
            EnumSet.of(RecoveryAction.RETRY_PAYMENT, RecoveryAction.CREATE_PAYMENT_LINK);

    private final TransactionRepository transactionRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final AuditLogRepository auditLogRepository;
    private final RecoveryAgentService recoveryAgentService;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;

    public RecoveryExecutionService(TransactionRepository transactionRepository,
                                     RecoveryAttemptRepository recoveryAttemptRepository,
                                     AuditLogRepository auditLogRepository,
                                     RecoveryAgentService recoveryAgentService,
                                     PaymentGateway paymentGateway,
                                     PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.recoveryAttemptRepository = recoveryAttemptRepository;
        this.auditLogRepository = auditLogRepository;
        this.recoveryAgentService = recoveryAgentService;
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Runs the full pipeline for one transaction. Takes no other input -
     * every fact the pipeline acts on (amount, currency, status, action)
     * is loaded from the database inside this call, so a client can never
     * supply or override them.
     */
    public RecoveryExecutionResponse execute(UUID transactionId) {
        AtomicReference<String> attemptedIdempotencyKey = new AtomicReference<>();
        try {
            RecoveryExecutionResponse response = transactionTemplate.execute(status ->
                    doExecute(transactionId, attemptedIdempotencyKey));
            return response;
        } catch (DataIntegrityViolationException lostRace) {
            String key = attemptedIdempotencyKey.get();
            if (key == null) {
                // Nothing reached the point of attempting a reservation - not our idempotency
                // constraint; a genuinely unexpected failure, so fail closed rather than guess.
                throw lostRace;
            }
            log.info("Recovery execution for transaction {} lost a concurrent race on idempotency key {}; resolving from the winning attempt.",
                    transactionId, key);
            return transactionTemplate.execute(status -> resolveDuplicate(transactionId, key));
        }
    }

    private RecoveryExecutionResponse doExecute(UUID transactionId, AtomicReference<String> attemptedIdempotencyKeyOut) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        var agentResponse = recoveryAgentService.evaluate(transactionId);
        PolicyDecision decision = agentResponse.policyDecision().decision();
        RecoveryAction action = agentResponse.finalAction();

        if (decision != PolicyDecision.ALLOW) {
            writeLifecycleAudit(transaction, eventTypeFor(decision), decision, action, null, null,
                    "No execution: policy decision was %s.".formatted(decision));
            return notExecutedResponse(transaction, agentResponse, false, null);
        }

        if (!GATEWAY_ACTIONS.contains(action)) {
            String note = "%s is not a payment-gateway action; no provider execution was performed.".formatted(action);
            writeLifecycleAudit(transaction, "RECOVERY_EXECUTION_NOT_APPLICABLE", decision, action, null, null, note);
            return notExecutedResponse(transaction, agentResponse, false, note);
        }

        int attemptNumber = recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transactionId).size() + 1;
        String idempotencyKey = IdempotencyKeys.forAttempt(transactionId, action, attemptNumber);
        attemptedIdempotencyKeyOut.set(idempotencyKey);

        Optional<RecoveryAttempt> existing = recoveryAttemptRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return duplicateResponse(transaction, agentResponse, existing.get());
        }

        // Reserve the attempt before calling the provider - see class javadoc for why this,
        // together with the unique constraint, is what makes "at most one provider call" safe
        // under concurrency without any additional locking infrastructure.
        RecoveryAttempt reserved = recoveryAttemptRepository.saveAndFlush(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(action)
                .status(RecoveryAttemptStatus.PENDING)
                .attemptNumber(attemptNumber)
                .idempotencyKey(idempotencyKey)
                .amount(transaction.getAmount())
                .reason("Recovery execution authorized by policy: ALLOW.")
                .executedAt(Instant.now())
                .build());

        writeLifecycleAudit(transaction, "RECOVERY_EXECUTION_STARTED", decision, action, reserved.getId(), null,
                "Execution authorized and started.");

        PaymentExecutionRequest request = new PaymentExecutionRequest(
                transactionId, transaction.getExternalTransactionId(), action,
                transaction.getAmount(), transaction.getCurrency(), idempotencyKey);
        PaymentExecutionResult result = paymentGateway.execute(request);

        reserved.setStatus(result.success() ? RecoveryAttemptStatus.SUCCESS : RecoveryAttemptStatus.FAILED);
        reserved.setResult(result.success()
                ? "Provider operation completed (%s); payment recovery not yet confirmed.".formatted(result.status())
                : result.failureReason());
        reserved.setAmountRecovered(result.amountRecovered());
        reserved.setProvider(result.provider());
        reserved.setProviderReference(result.providerReference());
        recoveryAttemptRepository.save(reserved);

        // See class javadoc: only a genuinely confirmed non-zero recovered amount transitions
        // the transaction - creating/sending a payment link never does, regardless of "success".
        if (result.success() && result.amountRecovered().compareTo(BigDecimal.ZERO) > 0) {
            transaction.setStatus(TransactionStatus.RECOVERED);
            transaction.setUpdatedAt(Instant.now());
            transactionRepository.save(transaction);
        }

        writeLifecycleAudit(transaction, result.success() ? "RECOVERY_EXECUTION_COMPLETED" : "RECOVERY_EXECUTION_FAILED",
                decision, action, reserved.getId(), result, null);

        return executedResponse(transaction, agentResponse, reserved, result, false, null);
    }

    /** Runs in a brand-new transaction (via {@link TransactionTemplate}) so the winning attempt's just-committed row is visible. */
    private RecoveryExecutionResponse resolveDuplicate(UUID transactionId, String idempotencyKey) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        RecoveryAttempt winner = recoveryAttemptRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Lost a concurrent execution race for idempotency key %s but no winning attempt was found."
                                .formatted(idempotencyKey)));

        return new RecoveryExecutionResponse(
                transaction.getId(), transaction.getExternalTransactionId(),
                null, null, false,
                winner.getStatus() == RecoveryAttemptStatus.SUCCESS,
                winner.getId(), winner.getAction(), winner.getProvider(), winner.getProviderReference(),
                winner.getStatus(), winner.getAmount(), zeroIfNull(winner.getAmountRecovered()),
                "mock".equals(winner.getProvider()), null, null, true,
                "A concurrent request already executed this recovery attempt; returning its result.",
                null, Instant.now(),
                confirmationStatus(winner), winner.getConfirmedAmount(), winner.getConfirmedCurrency(),
                winner.getProviderPaymentId(), winner.getConfirmedAt());
    }

    // ---------------------------------------------------------------- response builders

    private RecoveryExecutionResponse notExecutedResponse(Transaction transaction,
                                                            RecoveryAgentEvaluationResponse agentResponse,
                                                            boolean duplicate, String note) {
        return new RecoveryExecutionResponse(
                transaction.getId(), transaction.getExternalTransactionId(),
                agentResponse.aiRecommendation(), agentResponse.policyDecision(),
                agentResponse.requiresHumanApproval(), false, null, agentResponse.finalAction(),
                null, null, null, transaction.getAmount(), zero(), false,
                null, null, duplicate, note, agentResponse.auditEventId(), Instant.now(),
                PaymentConfirmationStatus.NOT_CONFIRMED, null, null, null, null);
    }

    private RecoveryExecutionResponse duplicateResponse(Transaction transaction,
                                                          RecoveryAgentEvaluationResponse agentResponse,
                                                          RecoveryAttempt existing) {
        return new RecoveryExecutionResponse(
                transaction.getId(), transaction.getExternalTransactionId(),
                agentResponse.aiRecommendation(), agentResponse.policyDecision(),
                agentResponse.requiresHumanApproval(),
                existing.getStatus() == RecoveryAttemptStatus.SUCCESS,
                existing.getId(), existing.getAction(), existing.getProvider(), existing.getProviderReference(),
                existing.getStatus(), existing.getAmount(), zeroIfNull(existing.getAmountRecovered()),
                "mock".equals(existing.getProvider()), null, null, true,
                "This exact recovery attempt was already executed; returning its result rather than calling the provider again.",
                agentResponse.auditEventId(), Instant.now(),
                confirmationStatus(existing), existing.getConfirmedAmount(), existing.getConfirmedCurrency(),
                existing.getProviderPaymentId(), existing.getConfirmedAt());
    }

    private RecoveryExecutionResponse executedResponse(Transaction transaction,
                                                         RecoveryAgentEvaluationResponse agentResponse,
                                                         RecoveryAttempt attempt, PaymentExecutionResult result,
                                                         boolean duplicate, String note) {
        return new RecoveryExecutionResponse(
                transaction.getId(), transaction.getExternalTransactionId(),
                agentResponse.aiRecommendation(), agentResponse.policyDecision(),
                agentResponse.requiresHumanApproval(), result.success(), attempt.getId(), attempt.getAction(),
                result.provider(), result.providerReference(), attempt.getStatus(),
                result.amount(), result.amountRecovered(), result.simulated(),
                result.failureCode(), result.failureReason(), duplicate, note,
                agentResponse.auditEventId(), Instant.now(),
                confirmationStatus(attempt), attempt.getConfirmedAmount(), attempt.getConfirmedCurrency(),
                attempt.getProviderPaymentId(), attempt.getConfirmedAt());
    }

    /** Every existing row predates Phase 12's not-null column default only in H2 test fixtures built by hand; defends against a null field regardless. */
    private static PaymentConfirmationStatus confirmationStatus(RecoveryAttempt attempt) {
        return attempt.getPaymentConfirmationStatus() == null
                ? PaymentConfirmationStatus.NOT_CONFIRMED : attempt.getPaymentConfirmationStatus();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? zero() : value;
    }

    private static String eventTypeFor(PolicyDecision decision) {
        return switch (decision) {
            case BLOCK -> "RECOVERY_EXECUTION_BLOCKED";
            case ESCALATE -> "RECOVERY_EXECUTION_ESCALATED";
            case STOP -> "RECOVERY_EXECUTION_STOPPED";
            case ALLOW -> "RECOVERY_EXECUTION_STARTED";
        };
    }

    // ---------------------------------------------------------------- audit trail

    private void writeLifecycleAudit(Transaction transaction, String eventType, PolicyDecision decision,
                                      RecoveryAction action, UUID recoveryAttemptId, PaymentExecutionResult result,
                                      String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (decision != null) metadata.put("policyDecision", decision.name());
        if (action != null) metadata.put("action", action.name());
        if (recoveryAttemptId != null) metadata.put("recoveryAttemptId", recoveryAttemptId.toString());
        if (result != null) {
            metadata.put("provider", result.provider());
            metadata.put("providerReference", result.providerReference() == null ? "" : result.providerReference());
            metadata.put("success", result.success());
            metadata.put("simulated", result.simulated());
            metadata.put("amount", result.amount());
            metadata.put("amountRecovered", result.amountRecovered());
            metadata.put("failureCode", result.failureCode() == null ? "" : result.failureCode().name());
        }
        String effectiveReason = reason != null ? reason
                : (result != null && !result.success() ? result.failureReason() : null);

        AuditLog audit = AuditLog.builder()
                .transaction(transaction)
                .eventType(eventType)
                .actor(ACTOR)
                .decision(decision == null ? null : decision.name())
                .reason(effectiveReason)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build();
        auditLogRepository.save(audit);
    }
}
