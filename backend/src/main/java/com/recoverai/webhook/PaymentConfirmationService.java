package com.recoverai.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverai.config.RazorpayProperties;
import com.recoverai.domain.AuditLog;
import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.domain.WebhookEvent;
import com.recoverai.domain.WebhookProcessingStatus;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies and processes inbound Razorpay payment-confirmation webhooks -
 * the only path in this system that can transition a {@link Transaction}
 * to {@link TransactionStatus#RECOVERED} or set a non-zero {@code
 * amountRecovered}. See {@code WebhookController} for the HTTP boundary.
 * <p>
 * <b>Never trusts a webhook until its signature is verified</b> - {@link
 * #processRazorpayWebhook} checks the signature over the raw payload
 * before parsing or acting on a single field of it (Phase 12 spec §2).
 * <p>
 * <b>Correlation.</b> A confirmation is matched to the {@link
 * RecoveryAttempt} whose {@code providerReference} (the Razorpay payment
 * link id it created - see {@code RazorpayPaymentGateway}) equals the
 * webhook's {@code payload.payment_link.entity.id} - never by amount
 * alone, and never by trusting a client-supplied transaction id (§4).
 * <p>
 * <b>Amount/currency verification.</b> The confirmed amount (converted from
 * paise using {@link BigDecimal}, never floating point) and currency must
 * exactly match the attempt's authorized amount/currency, or the
 * confirmation is rejected and the transaction state is left untouched
 * (§5) - see {@link #reject}.
 * <p>
 * <b>Idempotency.</b> {@code webhook_events.provider_event_id} carries a
 * real database unique constraint (migration V11). The first database
 * write this method makes is reserving that row; a concurrent or replayed
 * delivery of the same event loses that race with a {@link
 * DataIntegrityViolationException} and is resolved to the winner's already
 * -committed outcome, never reprocessed - the same pattern {@code
 * RecoveryExecutionService} uses for execution idempotency (§6).
 */
@Service
public class PaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);

    private static final String PROVIDER = "razorpay";
    private static final String CONFIRMED_EVENT_TYPE = "payment_link.paid";
    private static final BigDecimal PAISE_PER_RUPEE = new BigDecimal("100");

    private final RazorpayProperties razorpayProperties;
    private final WebhookEventRepository webhookEventRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final TransactionRepository transactionRepository;
    private final AuditLogRepository auditLogRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentConfirmationService(RazorpayProperties razorpayProperties,
                                       WebhookEventRepository webhookEventRepository,
                                       RecoveryAttemptRepository recoveryAttemptRepository,
                                       TransactionRepository transactionRepository,
                                       AuditLogRepository auditLogRepository,
                                       PlatformTransactionManager transactionManager) {
        this.razorpayProperties = razorpayProperties;
        this.webhookEventRepository = webhookEventRepository;
        this.recoveryAttemptRepository = recoveryAttemptRepository;
        this.transactionRepository = transactionRepository;
        this.auditLogRepository = auditLogRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public WebhookProcessingResult processRazorpayWebhook(String rawBody, String signatureHeader, String eventIdHeader) {
        if (!RazorpayWebhookSignature.isValid(rawBody, signatureHeader, razorpayProperties.getWebhookSecret())) {
            log.warn("Rejected a Razorpay webhook delivery with a missing or invalid signature.");
            return WebhookProcessingResult.invalidSignature();
        }

        ParsedWebhook parsed;
        try {
            parsed = parse(rawBody);
        } catch (Exception e) {
            log.warn("Rejected a Razorpay webhook delivery whose (signature-verified) payload was not valid JSON.");
            return new WebhookProcessingResult(WebhookOutcome.REJECTED, "Webhook payload was not valid JSON.", null);
        }

        String providerEventId = resolveProviderEventId(eventIdHeader, parsed, rawBody);
        AtomicBoolean reservedAttempted = new AtomicBoolean(false);
        try {
            return transactionTemplate.execute(status -> doProcess(parsed, providerEventId, reservedAttempted));
        } catch (DataIntegrityViolationException lostRace) {
            if (!reservedAttempted.get()) {
                throw lostRace;
            }
            log.info("Razorpay webhook event {} lost a concurrent idempotency race; resolving from the already-processed row.", providerEventId);
            return transactionTemplate.execute(status -> resolveAlreadyProcessed(providerEventId));
        }
    }

    private WebhookProcessingResult doProcess(ParsedWebhook parsed, String providerEventId, AtomicBoolean reservedAttempted) {
        reservedAttempted.set(true);
        WebhookEvent reserved = webhookEventRepository.saveAndFlush(WebhookEvent.builder()
                .provider(PROVIDER)
                .providerEventId(providerEventId)
                .eventType(parsed.eventType() == null ? "unknown" : parsed.eventType())
                .processingStatus(WebhookProcessingStatus.IGNORED)
                .receivedAt(Instant.now())
                .build());

        if (!CONFIRMED_EVENT_TYPE.equals(parsed.eventType())) {
            return finalizeIgnored(reserved, "Event type is not a recovery-confirmation event; no recovery state was changed.");
        }
        if (isBlank(parsed.paymentLinkId())) {
            return finalizeRejected(reserved, null, "Webhook payload was missing the payment link identifier.");
        }

        RecoveryAttempt attempt = recoveryAttemptRepository.findByProviderReference(parsed.paymentLinkId()).orElse(null);
        if (attempt == null) {
            return finalizeRejected(reserved, null, "No recovery attempt matches this payment link identifier.");
        }

        Transaction transaction = attempt.getTransaction();
        writeAudit(transaction, "PAYMENT_WEBHOOK_RECEIVED", attempt.getId(),
                "Received and signature-verified a Razorpay payment_link.paid webhook.",
                Map.of("recoveryAttemptId", attempt.getId().toString(), "providerEventId", providerEventId));

        if (attempt.getPaymentConfirmationStatus() == PaymentConfirmationStatus.CONFIRMED
                || transaction.getStatus() == TransactionStatus.RECOVERED) {
            reserved.setRecoveryAttempt(attempt);
            reserved.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
            reserved.setReason("Duplicate confirmation event; this attempt was already confirmed.");
            reserved.setProcessedAt(Instant.now());
            webhookEventRepository.save(reserved);
            writeAudit(transaction, "PAYMENT_CONFIRMATION_VERIFIED", attempt.getId(),
                    "Duplicate confirmation event received for an already-confirmed recovery attempt; no change made.", Map.of());
            return new WebhookProcessingResult(WebhookOutcome.ALREADY_PROCESSED,
                    "This recovery attempt was already confirmed.", attempt.getId());
        }

        if (attempt.getStatus() != RecoveryAttemptStatus.SUCCESS) {
            return finalizeRejected(reserved, attempt,
                    "Recovery attempt status is %s, not SUCCESS; a payment cannot be confirmed for it."
                            .formatted(attempt.getStatus()));
        }
        if (isBlank(parsed.paymentId()) || parsed.amountPaise() == null || isBlank(parsed.currency())) {
            return finalizeRejected(reserved, attempt, "Webhook payload was missing the payment id, amount, or currency.");
        }
        if (!transaction.getCurrency().equalsIgnoreCase(parsed.currency())) {
            return finalizeRejected(reserved, attempt, "Currency mismatch: expected %s, webhook reported %s."
                    .formatted(transaction.getCurrency(), parsed.currency()));
        }

        BigDecimal confirmedAmount = new BigDecimal(parsed.amountPaise())
                .divide(PAISE_PER_RUPEE, 2, RoundingMode.HALF_UP);
        if (confirmedAmount.compareTo(attempt.getAmount()) != 0) {
            return finalizeRejected(reserved, attempt, "Amount mismatch: expected %s, webhook reported %s."
                    .formatted(attempt.getAmount(), confirmedAmount));
        }

        return confirm(reserved, attempt, transaction, confirmedAmount, parsed.currency(), parsed.paymentId());
    }

    /** The only path that ever sets a non-zero confirmed amount or transitions a transaction to RECOVERED. */
    private WebhookProcessingResult confirm(WebhookEvent reserved, RecoveryAttempt attempt, Transaction transaction,
                                             BigDecimal confirmedAmount, String currency, String paymentId) {
        attempt.setPaymentConfirmationStatus(PaymentConfirmationStatus.CONFIRMED);
        attempt.setConfirmedAmount(confirmedAmount);
        attempt.setConfirmedCurrency(currency.toUpperCase(Locale.ROOT));
        attempt.setProviderPaymentId(paymentId);
        attempt.setConfirmedAt(Instant.now());
        attempt.setAmountRecovered(confirmedAmount);
        recoveryAttemptRepository.save(attempt);

        transaction.setStatus(TransactionStatus.RECOVERED);
        transaction.setUpdatedAt(Instant.now());
        transactionRepository.save(transaction);

        reserved.setRecoveryAttempt(attempt);
        reserved.setProcessingStatus(WebhookProcessingStatus.PROCESSED);
        reserved.setReason("Payment confirmed; transaction marked RECOVERED.");
        reserved.setProcessedAt(Instant.now());
        webhookEventRepository.save(reserved);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("recoveryAttemptId", attempt.getId().toString());
        metadata.put("providerPaymentId", paymentId);
        metadata.put("confirmedAmount", confirmedAmount);
        metadata.put("confirmedCurrency", attempt.getConfirmedCurrency());
        writeAudit(transaction, "PAYMENT_CONFIRMATION_VERIFIED", attempt.getId(),
                "Webhook signature, correlation, and amount/currency all verified.", metadata);
        writeAudit(transaction, "PAYMENT_RECOVERY_CONFIRMED", attempt.getId(),
                "Transaction marked RECOVERED from a verified payment confirmation.", metadata);

        log.info("Confirmed recovery attempt {} for transaction {}: {} {}.",
                attempt.getId(), transaction.getId(), confirmedAmount, attempt.getConfirmedCurrency());
        return new WebhookProcessingResult(WebhookOutcome.CONFIRMED,
                "Payment confirmed; transaction marked RECOVERED.", attempt.getId());
    }

    private WebhookProcessingResult finalizeIgnored(WebhookEvent reserved, String reason) {
        reserved.setProcessingStatus(WebhookProcessingStatus.IGNORED);
        reserved.setReason(reason);
        reserved.setProcessedAt(Instant.now());
        webhookEventRepository.save(reserved);
        return new WebhookProcessingResult(WebhookOutcome.IGNORED, reason, null);
    }

    /** {@code attempt} is null when no recovery attempt could be matched at all - nothing to mutate there, only the webhook_events row records the rejection. */
    private WebhookProcessingResult finalizeRejected(WebhookEvent reserved, RecoveryAttempt attempt, String reason) {
        reserved.setProcessingStatus(WebhookProcessingStatus.REJECTED);
        reserved.setReason(reason);
        reserved.setProcessedAt(Instant.now());
        Transaction transaction = null;
        if (attempt != null) {
            reserved.setRecoveryAttempt(attempt);
            attempt.setPaymentConfirmationStatus(PaymentConfirmationStatus.REJECTED);
            recoveryAttemptRepository.save(attempt);
            transaction = attempt.getTransaction();
        }
        webhookEventRepository.save(reserved);
        writeAudit(transaction, "PAYMENT_CONFIRMATION_REJECTED",
                attempt == null ? null : attempt.getId(), reason, Map.of());
        return new WebhookProcessingResult(WebhookOutcome.REJECTED, reason, attempt == null ? null : attempt.getId());
    }

    /** Runs in a brand-new transaction so the winning delivery's just-committed row is visible. */
    private WebhookProcessingResult resolveAlreadyProcessed(String providerEventId) {
        WebhookEvent winner = webhookEventRepository.findByProviderAndProviderEventId(PROVIDER, providerEventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Lost a concurrent webhook race for event %s but no winning row was found.".formatted(providerEventId)));
        return new WebhookProcessingResult(WebhookOutcome.ALREADY_PROCESSED,
                "This webhook event was already processed by a concurrent delivery.",
                winner.getRecoveryAttempt() == null ? null : winner.getRecoveryAttempt().getId());
    }

    /**
     * Audit rows require a non-null transaction (existing schema, unchanged
     * here by design). A webhook that never resolves to a specific
     * transaction (unrecognized event, missing identifier, no matching
     * attempt) is still fully recorded in {@code webhook_events} - it has
     * nowhere per-transaction to go, and inventing one would misattribute it.
     */
    private void writeAudit(Transaction transaction, String eventType, java.util.UUID recoveryAttemptId,
                             String reason, Map<String, Object> metadata) {
        if (transaction == null) {
            return;
        }
        Map<String, Object> fullMetadata = new LinkedHashMap<>(metadata);
        if (recoveryAttemptId != null) {
            fullMetadata.putIfAbsent("recoveryAttemptId", recoveryAttemptId.toString());
        }
        AuditLog audit = AuditLog.builder()
                .transaction(transaction)
                .eventType(eventType)
                .actor("PAYMENT_CONFIRMATION_SERVICE")
                .reason(reason)
                .metadata(fullMetadata)
                .timestamp(Instant.now())
                .build();
        auditLogRepository.save(audit);
    }

    // ---------------------------------------------------------------- payload parsing

    private ParsedWebhook parse(String rawBody) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawBody);
        String eventType = root.path("event").asText(null);
        String paymentLinkId = textOrNull(root.at("/payload/payment_link/entity/id"));
        String paymentId = textOrNull(root.at("/payload/payment/entity/id"));
        JsonNode amountNode = root.at("/payload/payment/entity/amount");
        Long amountPaise = amountNode.isNumber() ? amountNode.asLong() : null;
        String currency = textOrNull(root.at("/payload/payment/entity/currency"));
        return new ParsedWebhook(eventType, paymentLinkId, paymentId, amountPaise, currency);
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    private static String resolveProviderEventId(String eventIdHeader, ParsedWebhook parsed, String rawBody) {
        if (eventIdHeader != null && !eventIdHeader.isBlank()) {
            return eventIdHeader.trim();
        }
        String eventType = parsed.eventType() == null ? "unknown" : parsed.eventType();
        String fallbackId = parsed.paymentId() != null ? parsed.paymentId()
                : parsed.paymentLinkId() != null ? parsed.paymentLinkId()
                : Integer.toHexString(rawBody.hashCode());
        return eventType + ":" + fallbackId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record ParsedWebhook(String eventType, String paymentLinkId, String paymentId, Long amountPaise, String currency) {
    }
}
