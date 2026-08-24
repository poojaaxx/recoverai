package com.recoverai.seed;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the deterministic seed dataset against the H2 "test" profile.
 * Real-PostgreSQL verification of the same seeder lives in
 * {@code PostgresMigrationIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoDataSeederTest {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeederTest.class);

    @Autowired
    private DemoDataSeeder seeder;

    @Test
    void seedProducesExpectedVolumeAndDistribution() {
        SeedReport report = seeder.seed();

        log.info("Seed data quality report (H2):");
        log.info("Total transactions: {}", report.totalTransactions());
        report.countsByStatus().forEach((status, count) -> log.info("  {}: {}", status, count));
        log.info("High-value (>= 10000): {}", report.highValueCount());
        log.info("Repeated failures (attemptCount >= 2): {}", report.repeatedFailureCount());
        log.info("RevenueRisk rows: {}", report.revenueRiskCount());
        log.info("RecoveryAttempt rows: {}", report.recoveryAttemptCount());
        log.info("AuditLog rows: {}", report.auditLogCount());
        log.info("Demo transactions: {}", report.demoTransactionIds());

        assertThat(report.totalTransactions()).isEqualTo(DemoDataSeeder.TOTAL_TRANSACTIONS);

        assertThat(report.countsByStatus().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(DemoDataSeeder.TOTAL_TRANSACTIONS);
        assertThat(report.countsByStatus().get("SUCCESS")).isGreaterThan(0);
        assertThat(report.countsByStatus().get("FAILED")).isGreaterThan(0);
        assertThat(report.countsByStatus().get("PENDING")).isGreaterThan(0);
        assertThat(report.countsByStatus().get("ABANDONED")).isGreaterThan(0);
        assertThat(report.countsByStatus().get("RECOVERED")).isGreaterThan(0);
        assertThat(report.countsByStatus().get("ESCALATED")).isGreaterThan(0);
        assertThat(report.countsByStatus().get("STOPPED")).isGreaterThan(0);

        assertThat(report.highValueCount()).isGreaterThan(0);
        assertThat(report.repeatedFailureCount()).isGreaterThan(0);
        assertThat(report.revenueRiskCount()).isGreaterThan(0);
        assertThat(report.recoveryAttemptCount()).isGreaterThan(0);
        assertThat(report.auditLogCount()).isGreaterThan(0);

        assertThat(report.demoTransactionIds()).hasSize(DemoDataSeeder.DEMO_TRANSACTION_COUNT);
        assertThat(report.demoTransactionIds().values()).containsExactlyInAnyOrder(
                "demo-easy-recovery",
                "demo-retry-escalation",
                "demo-high-value",
                "demo-repeated-failure",
                "demo-successful-recovery"
        );
    }
}
