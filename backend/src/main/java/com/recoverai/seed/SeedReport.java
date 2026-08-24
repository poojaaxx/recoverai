package com.recoverai.seed;

import java.util.Map;

/**
 * Actual counts produced by a {@link DemoDataSeeder} run, read back from
 * what was persisted — never hardcoded. Intended to be logged and asserted
 * on in tests so the "data quality check" is always derived from the
 * database, not claimed.
 */
public record SeedReport(
        int totalTransactions,
        Map<String, Long> countsByStatus,
        long highValueCount,
        long repeatedFailureCount,
        long revenueRiskCount,
        long recoveryAttemptCount,
        long auditLogCount,
        Map<String, String> demoTransactionIds
) {
}
