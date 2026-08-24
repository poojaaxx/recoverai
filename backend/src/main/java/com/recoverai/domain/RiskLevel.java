package com.recoverai.domain;

/**
 * Application-level risk classification bands over the {@code 0-100}
 * {@link RevenueRisk#getRiskScore()} range established in Phase 2's
 * schema ({@code CHECK (risk_score BETWEEN 0 AND 100)}). {@code
 * recoveryProbability} is the separate {@code 0.0-1.0} metric — see
 * {@link RevenueRisk}. Thresholds (see {@code
 * recoverai.risk.risk-level-thresholds} in application.yml) are a
 * prototype classification, not an external financial or regulatory
 * standard.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
