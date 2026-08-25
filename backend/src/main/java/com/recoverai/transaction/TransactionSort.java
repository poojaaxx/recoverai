package com.recoverai.transaction;

import org.springframework.data.domain.Sort;

/**
 * The dashboard's supported sort options (Phase 13), each mapped to an
 * explicit, alias-qualified JPQL sort path (matching the {@code t}/{@code
 * r} aliases in {@code TransactionRepository#search}) rather than
 * accepting a raw client-supplied sort string. A transaction with no
 * {@code RevenueRisk} row sorts using the database's default NULL
 * ordering for the risk-based options, which is acceptable for a
 * dashboard sort (not a correctness-critical figure).
 */
public enum TransactionSort {
    NEWEST,
    OLDEST,
    AMOUNT_DESC,
    RISK_SCORE_DESC,
    AMOUNT_AT_RISK_DESC,
    RECOVERY_PROBABILITY_DESC;

    Sort toSort() {
        return switch (this) {
            case NEWEST -> Sort.by(Sort.Direction.DESC, "t.createdAt");
            case OLDEST -> Sort.by(Sort.Direction.ASC, "t.createdAt");
            case AMOUNT_DESC -> Sort.by(Sort.Direction.DESC, "t.amount");
            case RISK_SCORE_DESC -> Sort.by(Sort.Direction.DESC, "r.riskScore");
            case AMOUNT_AT_RISK_DESC -> Sort.by(Sort.Direction.DESC, "r.amountAtRisk");
            case RECOVERY_PROBABILITY_DESC -> Sort.by(Sort.Direction.DESC, "r.recoveryProbability");
        };
    }
}
