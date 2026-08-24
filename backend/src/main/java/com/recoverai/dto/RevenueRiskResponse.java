package com.recoverai.dto;

import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only projection of a {@link RevenueRisk} analysis result.
 * {@code riskScore} is 0-100; {@code recoveryProbability} is 0.0-1.0 -
 * deliberately distinct metrics, see {@code RevenueRiskService}.
 */
public record RevenueRiskResponse(
        UUID transactionId,
        String externalTransactionId,
        BigDecimal amount,
        String currency,
        BigDecimal amountAtRisk,
        BigDecimal riskScore,
        RiskLevel riskLevel,
        BigDecimal recoveryProbability,
        BigDecimal potentialRecoveryValue,
        List<String> factors,
        String reason,
        Instant analyzedAt
) {
    public static RevenueRiskResponse from(RevenueRisk risk) {
        BigDecimal potentialRecoveryValue = risk.getAmountAtRisk()
                .multiply(risk.getRecoveryProbability())
                .setScale(2, RoundingMode.HALF_UP);

        return new RevenueRiskResponse(
                risk.getTransaction().getId(),
                risk.getTransaction().getExternalTransactionId(),
                risk.getTransaction().getAmount(),
                risk.getTransaction().getCurrency(),
                risk.getAmountAtRisk(),
                risk.getRiskScore(),
                risk.getRiskLevel(),
                risk.getRecoveryProbability(),
                potentialRecoveryValue,
                risk.getFactors(),
                risk.getReason(),
                risk.getDetectedAt()
        );
    }
}
