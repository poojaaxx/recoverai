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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The output of a risk assessment for a transaction — one row per
 * transaction ({@code transaction_id} is unique; see V8), updated in place
 * each time {@code RevenueRiskService} re-analyzes it rather than
 * accumulating history rows. {@code riskScore} is 0-100 (Phase 2's
 * original scale), {@code recoveryProbability} is 0.0-1.0 — these are
 * deliberately distinct metrics, see {@link RiskLevel} and {@code
 * RevenueRiskService}. {@code detectedAt} reflects the most recent
 * analysis, not necessarily the first.
 */
@Entity
@Table(name = "revenue_risks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevenueRisk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "risk_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal riskScore;

    @Column(name = "recovery_probability", nullable = false, precision = 5, scale = 4)
    private BigDecimal recoveryProbability;

    @Column(name = "amount_at_risk", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountAtRisk;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private RiskLevel riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> factors;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private Instant detectedAt = Instant.now();
}
