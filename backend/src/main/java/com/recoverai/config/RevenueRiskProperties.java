package com.recoverai.config;

import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.RiskLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic weights and thresholds for {@code RevenueRiskService}.
 * Every number here is an application-invented, illustrative synthetic
 * weight for this prototype — not a statistically fitted or externally
 * validated model. Defaults mirror {@code application.yml} so the engine
 * behaves sensibly even if that section is omitted.
 */
@Component
@ConfigurationProperties(prefix = "recoverai.risk")
@Getter
@Setter
public class RevenueRiskProperties {

    /** Baseline recovery-potential weight (0-1) per synthetic failure category. */
    private Map<FailureCategory, BigDecimal> failureCategoryRecoveryWeights = defaultCategoryWeights();

    /** Baseline recovery-potential weight for PENDING transactions (no failure has occurred yet). */
    private BigDecimal pendingBaselineRecoveryWeight = new BigDecimal("0.60");

    /** Baseline recovery-potential weight for ABANDONED checkouts (harder to win back than a retryable failure). */
    private BigDecimal abandonedBaselineRecoveryWeight = new BigDecimal("0.35");

    /** Multiplier applied to recoveryProbability once a transaction has left the automated retry path (ESCALATED/STOPPED). */
    private BigDecimal terminalStateRecoveryFactor = new BigDecimal("0.50");

    private AmountThresholds amountThresholds = new AmountThresholds();
    private AmountFactors amountFactors = new AmountFactors();
    private RiskWeights weights = new RiskWeights();
    private HistoryConfig history = new HistoryConfig();
    private AttemptsConfig attempts = new AttemptsConfig();
    private RiskLevelThresholds riskLevelThresholds = new RiskLevelThresholds();

    private static Map<FailureCategory, BigDecimal> defaultCategoryWeights() {
        Map<FailureCategory, BigDecimal> weights = new LinkedHashMap<>();
        weights.put(FailureCategory.TEMPORARY_FAILURE, new BigDecimal("0.80"));
        weights.put(FailureCategory.NETWORK_ERROR, new BigDecimal("0.75"));
        weights.put(FailureCategory.INSUFFICIENT_FUNDS, new BigDecimal("0.55"));
        weights.put(FailureCategory.LIMIT_EXCEEDED, new BigDecimal("0.45"));
        weights.put(FailureCategory.BANK_DECLINED, new BigDecimal("0.35"));
        weights.put(FailureCategory.AUTHENTICATION_FAILURE, new BigDecimal("0.30"));
        weights.put(FailureCategory.UNKNOWN, new BigDecimal("0.25"));
        return weights;
    }

    @Getter
    @Setter
    public static class AmountThresholds {
        private BigDecimal low = new BigDecimal("1000");
        private BigDecimal mid = new BigDecimal("10000");
        private BigDecimal high = new BigDecimal("30000");
    }

    /**
     * Amount-severity sub-scores (0-1) for each bucket defined by {@link
     * AmountThresholds}. Amount is weighted heavily in {@link RiskWeights}
     * precisely so that a large exposure can dominate riskScore even when
     * the failure is an easy, likely-recoverable one — see the worked
     * example in RevenueRiskService's javadoc (₹47,500 temporary failure
     * from a strong-history customer: HIGH risk score, high recovery
     * probability, both at once).
     */
    @Getter
    @Setter
    public static class AmountFactors {
        private BigDecimal low = new BigDecimal("0.10");
        private BigDecimal mid = new BigDecimal("0.30");
        private BigDecimal high = new BigDecimal("0.60");
        private BigDecimal veryHigh = new BigDecimal("0.92");
    }

    /** Risk-score component weights. Should sum to 1.0. */
    @Getter
    @Setter
    public static class RiskWeights {
        private BigDecimal amount = new BigDecimal("0.65");
        private BigDecimal failure = new BigDecimal("0.18");
        private BigDecimal history = new BigDecimal("0.05");
        private BigDecimal attempts = new BigDecimal("0.12");
    }

    @Getter
    @Setter
    public static class HistoryConfig {
        /** historyScore >= this => STRONG_CUSTOMER_HISTORY factor. */
        private BigDecimal strongThreshold = new BigDecimal("0.75");
        /** historyScore <= this => WEAK_CUSTOMER_HISTORY factor. */
        private BigDecimal weakThreshold = new BigDecimal("0.35");
        /** How much history can shift recoveryProbability, +/- (weight * 0.5). */
        private BigDecimal probabilityAdjustmentWeight = new BigDecimal("0.30");
    }

    @Getter
    @Setter
    public static class AttemptsConfig {
        /** recoveryProbability lost per prior attempt (gateway attempts beyond the first, plus failed recovery attempts). */
        private BigDecimal probabilityPenaltyPerUnit = new BigDecimal("0.15");
        /** riskScore attempt-urgency contribution gained per prior attempt, capped at 1.0. */
        private BigDecimal riskUrgencyPerUnit = new BigDecimal("0.20");
    }

    @Getter
    @Setter
    public static class RiskLevelThresholds {
        private BigDecimal medium = new BigDecimal("30");
        private BigDecimal high = new BigDecimal("60");
        private BigDecimal critical = new BigDecimal("80");
    }

    public RiskLevel classify(BigDecimal riskScore) {
        if (riskScore.compareTo(riskLevelThresholds.getCritical()) >= 0) return RiskLevel.CRITICAL;
        if (riskScore.compareTo(riskLevelThresholds.getHigh()) >= 0) return RiskLevel.HIGH;
        if (riskScore.compareTo(riskLevelThresholds.getMedium()) >= 0) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
