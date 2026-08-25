package com.recoverai.webhook;

import java.util.UUID;

/**
 * The internal, safe-to-expose outcome of processing one webhook delivery -
 * never contains a secret, a signature, or the raw payload. See {@code
 * WebhookController} for how this maps to an HTTP response.
 */
public record WebhookProcessingResult(WebhookOutcome outcome, String reason, UUID recoveryAttemptId) {

    public static WebhookProcessingResult invalidSignature() {
        return new WebhookProcessingResult(WebhookOutcome.INVALID_SIGNATURE, "Invalid or missing webhook signature.", null);
    }
}
