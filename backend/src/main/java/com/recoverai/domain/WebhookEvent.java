package com.recoverai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

/**
 * A single received provider webhook delivery, recorded before any business
 * processing happens (migration V11). {@code providerEventId} carries a real
 * database uniqueness constraint (scoped per {@code provider}) — this row's
 * insert is what actually makes duplicate/replayed webhook deliveries safe
 * under concurrency, the same pattern {@code RecoveryAttempt.idempotencyKey}
 * uses for execution requests (see {@code
 * com.recoverai.webhook.PaymentConfirmationService}). Deliberately does not
 * store the raw webhook payload — only the minimal identifiers needed to
 * explain what happened, consistent with this project's data-minimization
 * approach (see {@code TransactionDetailResponse}'s PII masking).
 */
@Entity
@Table(name = "webhook_events", uniqueConstraints = @UniqueConstraint(
        name = "uq_webhook_events_provider_event", columnNames = {"provider", "provider_event_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_event_id", nullable = false, length = 255)
    private String providerEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private WebhookProcessingStatus processingStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recovery_attempt_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RecoveryAttempt recoveryAttempt;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "received_at", nullable = false)
    @Builder.Default
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;
}
