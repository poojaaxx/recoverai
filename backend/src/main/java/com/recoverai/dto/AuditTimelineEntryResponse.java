package com.recoverai.dto;

import com.recoverai.domain.AuditLog;

import java.time.Instant;
import java.util.UUID;

/** A single real, persisted {@link AuditLog} row, shaped for a compact demo timeline. Never fabricated. */
public record AuditTimelineEntryResponse(
        UUID id,
        String eventType,
        String actor,
        String decision,
        String reason,
        Instant timestamp
) {
    public static AuditTimelineEntryResponse from(AuditLog log) {
        return new AuditTimelineEntryResponse(log.getId(), log.getEventType(), log.getActor(),
                log.getDecision(), log.getReason(), log.getTimestamp());
    }
}
