package com.recoverai.dto;

import com.recoverai.domain.AuditLog;

import java.time.Instant;
import java.util.UUID;

/**
 * P1.4 - one row of the portfolio-wide audit feed ({@code GET
 * /api/audit}), distinct from {@link AuditTimelineEntryResponse} only in
 * that it also identifies which transaction the event belongs to, since a
 * global feed spans every transaction rather than one already-known one.
 * Same real, persisted {@link AuditLog} row - nothing invented.
 */
public record GlobalAuditEntryResponse(
        UUID id,
        UUID transactionId,
        String externalTransactionId,
        String eventType,
        String actor,
        String decision,
        String reason,
        Instant timestamp
) {
    public static GlobalAuditEntryResponse from(AuditLog log) {
        return new GlobalAuditEntryResponse(log.getId(), log.getTransaction().getId(),
                log.getTransaction().getExternalTransactionId(), log.getEventType(), log.getActor(),
                log.getDecision(), log.getReason(), log.getTimestamp());
    }
}
