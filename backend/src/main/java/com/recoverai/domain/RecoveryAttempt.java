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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single recorded recovery action against a transaction — whether it was
 * actually executed, blocked by the safety policy engine, or represents
 * historical/seeded data for demo purposes. See {@link
 * RecoveryAttemptStatus} for the BLOCKED vs FAILED distinction.
 */
@Entity
@Table(name = "recovery_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RecoveryAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecoveryAttemptStatus status;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "amount_recovered", precision = 14, scale = 2)
    private BigDecimal amountRecovered;

    /**
     * Deterministic key (see {@code com.recoverai.payment.IdempotencyKeys})
     * preventing the same recovery attempt from producing two provider
     * executions - enforced by a real database unique constraint
     * (migration V9). Nullable: rows from before Phase 6 (seed data,
     * Phase 2-5 test fixtures) never set it.
     */
    @Column(name = "idempotency_key", length = 200, unique = true)
    private String idempotencyKey;

    /**
     * The amount this attempt was authorized to act on, recorded as a
     * point-in-time fact rather than only derived via the {@code
     * transaction} relationship (migration V10, Phase 7).
     */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** {@code "mock"} or {@code "razorpay"} - null for rows that never involved a real {@code PaymentGateway} call (all seed data). */
    @Column(length = 20)
    private String provider;

    @Column(name = "provider_reference")
    private String providerReference;

    @Column(name = "executed_at", nullable = false)
    @Builder.Default
    private Instant executedAt = Instant.now();

    /**
     * Whether a verified provider webhook has confirmed this attempt as an
     * actual customer payment (migration V11, Phase 12) — strictly separate
     * from {@link #status}, which only reflects whether the provider call
     * itself succeeded. See {@link PaymentConfirmationStatus} and {@code
     * com.recoverai.webhook.PaymentConfirmationService}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_confirmation_status", nullable = false, length = 20)
    @Builder.Default
    private PaymentConfirmationStatus paymentConfirmationStatus = PaymentConfirmationStatus.NOT_CONFIRMED;

    /** The amount a verified webhook reported as actually paid — set only when {@link #paymentConfirmationStatus} is {@code CONFIRMED} or {@code REJECTED}. */
    @Column(name = "confirmed_amount", precision = 14, scale = 2)
    private BigDecimal confirmedAmount;

    @Column(name = "confirmed_currency", length = 3)
    private String confirmedCurrency;

    /** Razorpay's payment id (distinct from {@link #providerReference}, which is the payment *link* id this attempt created). */
    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;
}
