package com.recoverai.dto;

/** Result of a batch risk-analysis run over all currently at-risk-eligible transactions. */
public record BatchRiskAnalysisResponse(
        int transactionsAnalyzed,
        RevenueRiskMetricsResponse metrics
) {
}
