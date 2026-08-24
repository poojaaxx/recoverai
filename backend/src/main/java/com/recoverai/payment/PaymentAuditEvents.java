package com.recoverai.payment;

import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Transaction;

import java.time.Instant;
import java.util.Map;

/**
 * Builds the (unpersisted) {@link AuditLog} row shape for a {@link
 * PaymentExecutionResult} - {@code eventType=PAYMENT_PROVIDER_EXECUTION},
 * {@code actor=PAYMENT_GATEWAY}. Deliberately a pure builder, not a
 * service: nothing in Phase 6 calls this in production yet, because
 * nothing yet orchestrates "authorize via Phase 4, then execute via
 * {@link PaymentGateway}" end to end - that orchestration is Phase 7's
 * responsibility. This class exists so that wiring, when it lands, only
 * has to call {@code auditLogRepository.save(PaymentAuditEvents.forResult(...))}
 * with an already-correct, already-tested event shape - see {@code
 * PaymentAuditEventsTest}.
 * <p>
 * Metadata never includes API keys, secrets, or Authorization headers -
 * only the fields explicitly listed here.
 */
public final class PaymentAuditEvents {

    public static final String EVENT_TYPE = "PAYMENT_PROVIDER_EXECUTION";
    public static final String ACTOR = "PAYMENT_GATEWAY";

    private PaymentAuditEvents() {
    }

    public static AuditLog forResult(Transaction transaction, PaymentExecutionResult result) {
        return AuditLog.builder()
                .transaction(transaction)
                .eventType(EVENT_TYPE)
                .actor(ACTOR)
                .decision(result.success() ? "SUCCESS" : "FAILED")
                .reason(result.success()
                        ? "Payment provider operation completed; no confirmed recovery yet (see amountRecovered)."
                        : result.failureReason())
                .metadata(Map.of(
                        "provider", result.provider(),
                        "action", result.action().name(),
                        "providerReference", result.providerReference() == null ? "" : result.providerReference(),
                        "simulated", result.simulated(),
                        "success", result.success(),
                        "failureCode", result.failureCode() == null ? "" : result.failureCode().name(),
                        "amountRecovered", result.amountRecovered()
                ))
                .timestamp(Instant.now())
                .build();
    }
}
