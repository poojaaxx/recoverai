package com.recoverai.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverai.config.RazorpayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Real Razorpay payment execution via the documented Payment Links API
 * (<a href="https://razorpay.com/docs/api/payment-links/">{@code POST
 * /v1/payment_links}</a>) - selected only when {@code
 * recoverai.razorpay.enabled=true} <b>and</b> {@code
 * recoverai.razorpay.mode=test}.
 * <p>
 * <b>Mapping (documented per the Phase 6 spec's "document the mapping
 * explicitly" requirement):</b> both {@code RETRY_PAYMENT} and {@code
 * CREATE_PAYMENT_LINK} are implemented as creating a fresh Razorpay
 * Payment Link. Razorpay has no generic "retry the original charge" API
 * for checkout-initiated payments (the original payment attempt is not
 * re-chargeable without new customer authorization) - the realistic way
 * a merchant automates a payment retry is by sending the customer a new
 * payment link for the same amount, which is exactly what this
 * implementation does for both actions. {@code SEND_RECOVERY_REMINDER},
 * {@code ESCALATE}, and {@code STOP} are rejected before any network call
 * (see {@link PaymentGatewayValidation}) - they are not payment
 * operations.
 * <p>
 * <b>Unverified in this environment</b> - no Razorpay Test Mode
 * credentials were available while building this project, so this class
 * has never been exercised against the real API (see the Phase 6 report's
 * "Known limitations"). It is written defensively for exactly that
 * reason: {@link #execute} never throws - any network error, non-2xx
 * response, timeout, or response that fails validation is caught and
 * converted into a structured {@code success=false} {@link
 * PaymentExecutionResult}, so a bug here can only ever look like a
 * provider failure, never bypass validation or silently claim success.
 */
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);
    private static final String PROVIDER_NAME = "razorpay";
    private static final String PAYMENT_LINKS_PATH = "/v1/payment_links";
    private static final BigDecimal PAISE_PER_RUPEE = new BigDecimal("100");

    private final RazorpayProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RazorpayPaymentGateway(RazorpayProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public PaymentExecutionResult execute(PaymentExecutionRequest request) {
        var invalid = PaymentGatewayValidation.validate(request);
        if (invalid.isPresent()) {
            return failure(request, invalid.get().reason(), invalid.get().message());
        }
        if (properties.getKeyId().isBlank() || properties.getKeySecret().isBlank()) {
            return failure(request, PaymentFailureReason.AUTHENTICATION_FAILURE,
                    "Razorpay mode is enabled but no API credentials are configured.");
        }

        try {
            long amountInPaise = request.amount().multiply(PAISE_PER_RUPEE)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();

            Map<String, Object> requestBody = Map.of(
                    "amount", amountInPaise,
                    "currency", request.currency(),
                    "description", "RecoverAI recovery: %s for transaction %s"
                            .formatted(request.action(), request.externalTransactionId()),
                    // Razorpay caps reference_id at 40 characters; the real idempotency key can
                    // exceed that, so a separate, deterministic, collision-resistant value is
                    // derived for this field only - see RazorpayReferenceIds' javadoc. The full
                    // idempotency key is untouched everywhere else (recovery_attempts table, the
                    // duplicate-action check, etc.).
                    "reference_id", RazorpayReferenceIds.forIdempotencyKey(request.idempotencyKey()),
                    "notes", Map.of(
                            "transactionId", request.transactionId().toString(),
                            "action", request.action().name()
                    )
            );

            String basicAuth = Base64.getEncoder().encodeToString(
                    (properties.getKeyId() + ":" + properties.getKeySecret()).getBytes(StandardCharsets.UTF_8));

            String responseBody = webClient.post()
                    .uri(properties.getBaseUrl() + PAYMENT_LINKS_PATH)
                    .header("Authorization", "Basic " + basicAuth)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(properties.getTimeoutSeconds()));

            return parseResult(request, responseBody, amountInPaise);
        } catch (WebClientResponseException e) {
            PaymentFailureReason reason = mapHttpStatus(e.getStatusCode().value());
            logRazorpayError(request.transactionId(), e.getStatusCode().value(), e.getResponseBodyAsString());
            return failure(request, reason, "Razorpay API returned HTTP %d.".formatted(e.getStatusCode().value()));
        } catch (Exception e) {
            log.warn("Razorpay API call failed for transaction {}: {}", request.transactionId(), e.toString());
            return failure(request, PaymentFailureReason.PROVIDER_UNAVAILABLE, "Razorpay API call failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Never trust the raw provider response - validate identity, amount, and status before
     * ever claiming success. Package-private (not {@code private}) specifically so {@code
     * RazorpayPaymentGatewayTest} can exercise real Razorpay response payloads (including
     * {@code short_url}) directly, without standing up an HTTP server to mock the network call.
     */
    PaymentExecutionResult parseResult(PaymentExecutionRequest request, String responseBody, long expectedAmountPaise) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception e) {
            return failure(request, PaymentFailureReason.MALFORMED_RESPONSE, "Razorpay response was not valid JSON.");
        }

        String id = root.path("id").asText(null);
        String status = root.path("status").asText(null);
        long responseAmount = root.path("amount").asLong(-1);
        String shortUrl = root.path("short_url").asText(null);
        if (shortUrl != null && shortUrl.isBlank()) {
            shortUrl = null;
        }

        if (id == null || id.isBlank() || status == null) {
            return failure(request, PaymentFailureReason.MALFORMED_RESPONSE, "Razorpay response was missing required fields (id/status).");
        }
        if (responseAmount != expectedAmountPaise) {
            return failure(request, PaymentFailureReason.AMOUNT_MISMATCH,
                    "Razorpay-reported amount did not match the requested amount.");
        }
        if (!"created".equals(status)) {
            return failure(request, PaymentFailureReason.DECLINED, "Razorpay reported an unsuccessful status: " + status);
        }

        // A created payment link is not itself confirmed recovery - see PaymentExecutionResult's javadoc.
        // shortUrl is purely informational (the payable link) - it plays no role in this success
        // determination, which is based only on id/status/amount above.
        return new PaymentExecutionResult(
                true, PROVIDER_NAME, id, request.transactionId(), request.action(),
                request.amount(), request.currency(), zero(), false, status,
                null, null, request.idempotencyKey(), Instant.now(), shortUrl);
    }

    private static PaymentFailureReason mapHttpStatus(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> PaymentFailureReason.AUTHENTICATION_FAILURE;
            case 400, 422 -> PaymentFailureReason.INVALID_REQUEST;
            case 408 -> PaymentFailureReason.TIMEOUT;
            case 429 -> PaymentFailureReason.RATE_LIMITED;
            default -> statusCode >= 500 ? PaymentFailureReason.PROVIDER_UNAVAILABLE : PaymentFailureReason.UNKNOWN;
        };
    }

    private static PaymentExecutionResult failure(PaymentExecutionRequest request, PaymentFailureReason reason, String message) {
        return new PaymentExecutionResult(
                false, PROVIDER_NAME, null, request.transactionId(), request.action(),
                request.amount(), request.currency(), zero(), false, "failed",
                reason, message, request.idempotencyKey(), Instant.now(), null);
    }

    /**
     * Logs only the safe, structured parts of Razorpay's own error body - {@code error.code},
     * {@code error.description}, {@code error.field}, {@code error.reason} (see <a
     * href="https://razorpay.com/docs/api/errors/">Razorpay's documented error object
     * shape</a>) - never the raw request or response bytes, which is the only way an
     * Authorization header or credential could ever end up in a log line. This method never
     * receives or logs {@link RazorpayProperties#getKeyId()}/{@link
     * RazorpayProperties#getKeySecret()} - it only ever sees Razorpay's response, not the
     * request this class sent.
     */
    private void logRazorpayError(UUID transactionId, int statusCode, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            log.warn("Razorpay API call failed for transaction {}: HTTP {} (no response body).", transactionId, statusCode);
            return;
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            log.warn("Razorpay API call failed for transaction {}: HTTP {} code={} description={} field={} reason={}",
                    transactionId, statusCode,
                    error.path("code").asText("unknown"),
                    error.path("description").asText("none"),
                    error.path("field").asText("none"),
                    error.path("reason").asText("none"));
        } catch (Exception parseError) {
            log.warn("Razorpay API call failed for transaction {}: HTTP {} (response body was not parseable JSON).", transactionId, statusCode);
        }
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
}
