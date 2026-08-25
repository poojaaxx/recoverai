package com.recoverai.webhook;

/** What happened when processing one inbound webhook delivery - see {@link WebhookProcessingResult}. */
public enum WebhookOutcome {
    /** Signature missing or invalid - the payload was never trusted or processed further. */
    INVALID_SIGNATURE,
    /** Signature valid, but this delivery repeats a {@code providerEventId} already processed - no state was changed again. */
    ALREADY_PROCESSED,
    /** Signature valid, but the event type is not one this system acts on. */
    IGNORED,
    /** Signature valid, but the event could not be trusted as confirmation of a specific recovery attempt (see {@code reason}). */
    REJECTED,
    /** Signature valid, matched a specific recovery attempt, and the confirmed amount/currency matched exactly - the transaction is now RECOVERED. */
    CONFIRMED
}
