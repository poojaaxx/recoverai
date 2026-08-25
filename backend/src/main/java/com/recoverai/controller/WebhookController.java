package com.recoverai.controller;

import com.recoverai.webhook.PaymentConfirmationService;
import com.recoverai.webhook.WebhookOutcome;
import com.recoverai.webhook.WebhookProcessingResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inbound provider webhooks. This is the <b>only</b> path in the system
 * that can result in a transaction becoming {@code RECOVERED} or a
 * non-zero confirmed amount - see {@link PaymentConfirmationService}.
 * <p>
 * The request body is read exactly as received (a raw string, never
 * pre-parsed) because Razorpay's signature is computed over those exact
 * bytes - signature verification happens first, inside the service, before
 * a single field of the payload is trusted (Phase 12 spec §2). This
 * controller never logs the signature or the configured webhook secret,
 * and never echoes the payload back in a response.
 */
@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    /** Real Razorpay webhook payloads are a few KB; this is generous headroom, not a tuned limit. */
    private static final int MAX_BODY_BYTES = 65_536;

    private final PaymentConfirmationService paymentConfirmationService;

    public WebhookController(PaymentConfirmationService paymentConfirmationService) {
        this.paymentConfirmationService = paymentConfirmationService;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Map<String, Object>> razorpay(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {

        String body = rawBody == null ? "" : rawBody;
        if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Payload too large."));
        }

        WebhookProcessingResult result = paymentConfirmationService.processRazorpayWebhook(body, signature, eventId);

        if (result.outcome() == WebhookOutcome.INVALID_SIGNATURE) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid webhook signature."));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.outcome().name());
        response.put("reason", result.reason());
        return ResponseEntity.ok(response);
    }
}
