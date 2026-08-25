package com.recoverai.domain;

/** Outcome of processing one persisted {@link WebhookEvent}. */
public enum WebhookProcessingStatus {
    /** Signature verified, matched a recovery attempt, amount/currency confirmed - the attempt was (or already was) confirmed. */
    PROCESSED,
    /** Signature verified but the event could not be trusted as a valid confirmation (no match, amount/currency mismatch, ineligible attempt). */
    REJECTED,
    /** Signature verified but the event type is not one this system acts on - acknowledged, no state change. */
    IGNORED
}
