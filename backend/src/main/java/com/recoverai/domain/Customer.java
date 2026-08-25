package com.recoverai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Unidirectional {@code @ManyToOne} to {@link Merchant} only — there is no
 * inverse {@code @OneToMany} collection on {@code Merchant}. Later phases
 * that need "all customers for a merchant" query {@link
 * com.recoverai.repository.CustomerRepository} directly, which keeps
 * entity graphs simple and avoids lazy-loading/serialization surprises.
 */
@Entity
@Table(name = "customers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    private String phone;

    @Column(name = "successful_payment_count", nullable = false)
    @Builder.Default
    private int successfulPaymentCount = 0;

    @Column(name = "failed_payment_count", nullable = false)
    @Builder.Default
    private int failedPaymentCount = 0;

    @Column(name = "total_historical_value", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal totalHistoricalValue = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Phase 14 minimal compliance boundary - when {@code false}, {@code
     * RecoveryPolicyService} blocks every autonomous recovery action for
     * this customer's transactions (see {@code checkConsent}), including
     * batch execution. Server-side only: no client input can set or
     * override this field through any recovery endpoint.
     */
    @Column(name = "recovery_contact_allowed", nullable = false)
    @Builder.Default
    private boolean recoveryContactAllowed = true;
}
