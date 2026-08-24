package com.recoverai.risk;

import com.recoverai.domain.RiskLevel;
import com.recoverai.dto.BatchRiskAnalysisResponse;
import com.recoverai.dto.RevenueRiskMetricsResponse;
import com.recoverai.dto.RevenueRiskResponse;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.seed.DemoDataSeeder;
import com.recoverai.seed.SeedReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch risk analysis over the full deterministic 500-transaction seed
 * dataset (H2 "test" profile). Verifies aggregate metrics are internally
 * consistent, resolved transactions never count as at-risk, and the named
 * demo transactions land in the risk profile each is meant to demonstrate.
 */
@SpringBootTest
@ActiveProfiles("test")
class RevenueRiskBatchTest {

    private static final Logger log = LoggerFactory.getLogger(RevenueRiskBatchTest.class);

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RevenueRiskService revenueRiskService;
    @Autowired
    private RevenueRiskRepository revenueRiskRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void batchAnalysisOverSeedDataset_isConsistentAndDeterministic() {
        SeedReport seedReport = seeder.seed();
        long expectedEligible = seedReport.countsByStatus().get("FAILED")
                + seedReport.countsByStatus().get("PENDING")
                + seedReport.countsByStatus().get("ABANDONED")
                + seedReport.countsByStatus().get("ESCALATED")
                + seedReport.countsByStatus().get("STOPPED");

        BatchRiskAnalysisResponse firstRun = revenueRiskService.analyzeAllAtRisk();
        RevenueRiskMetricsResponse metrics = firstRun.metrics();

        log.info("Batch risk analysis over {} seeded transactions:", seedReport.totalTransactions());
        log.info("  Transactions analyzed: {}", firstRun.transactionsAnalyzed());
        log.info("  At-risk transactions: {}", metrics.atRiskTransactions());
        log.info("  Total transaction value: {}", metrics.totalTransactionValue());
        log.info("  Total revenue collected: {}", metrics.totalRevenueCollected());
        log.info("  Revenue at risk: {}", metrics.revenueAtRisk());
        log.info("  High-risk revenue: {}", metrics.highRiskRevenue());
        log.info("  Critical-risk revenue: {}", metrics.criticalRiskRevenue());
        log.info("  Average recovery probability: {}", metrics.averageRecoveryProbability());
        log.info("  Potentially recoverable revenue: {}", metrics.potentiallyRecoverableRevenue());

        assertThat(firstRun.transactionsAnalyzed()).isEqualTo((int) expectedEligible);
        assertThat(metrics.totalTransactions()).isEqualTo(seedReport.totalTransactions());
        assertThat(metrics.atRiskTransactions()).isEqualTo(expectedEligible);
        assertThat(metrics.revenueAtRisk()).isGreaterThan(BigDecimal.ZERO);
        assertThat(metrics.highRiskRevenue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(metrics.criticalRiskRevenue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(metrics.averageRecoveryProbability())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.ONE);
        assertThat(metrics.potentiallyRecoverableRevenue())
                .isGreaterThan(BigDecimal.ZERO)
                .isLessThanOrEqualTo(metrics.revenueAtRisk());

        // RECOVERED transactions must never count as at-risk, even though
        // the seed data itself gave them a (now-stale) non-zero seed-heuristic
        // risk row - see RevenueRiskService.correctStaleResolvedRiskRows().
        long recoveredCount = seedReport.countsByStatus().get("RECOVERED");
        assertThat(recoveredCount).isGreaterThan(0);
        transactionRepository.findByStatus(com.recoverai.domain.TransactionStatus.RECOVERED).forEach(txn ->
                revenueRiskRepository.findByTransactionId(txn.getId()).ifPresent(risk ->
                        assertThat(risk.getAmountAtRisk()).isEqualByComparingTo(BigDecimal.ZERO)));

        // Determinism: re-running the batch over the same data must not change any figure,
        // and must update rows in place rather than accumulating duplicates.
        long rowCountBeforeRerun = revenueRiskRepository.count();
        BatchRiskAnalysisResponse secondRun = revenueRiskService.analyzeAllAtRisk();
        assertThat(secondRun.transactionsAnalyzed()).isEqualTo(firstRun.transactionsAnalyzed());
        assertThat(secondRun.metrics()).isEqualTo(metrics);
        assertThat(revenueRiskRepository.count()).isEqualTo(rowCountBeforeRerun);
    }

    @Test
    void demoTransactions_reflectTheirIntendedRiskProfile() {
        SeedReport seedReport = seeder.seed();
        revenueRiskService.analyzeAllAtRisk();

        RevenueRiskResponse easyRecovery = analyzeByExternalId(seedReport.demoTransactionIds().get("easy_recovery"));
        assertThat(easyRecovery.recoveryProbability())
                .as("demo-easy-recovery should be recovery-friendly")
                .isGreaterThanOrEqualTo(new BigDecimal("0.60"));

        RevenueRiskResponse retryEscalation = analyzeByExternalId(seedReport.demoTransactionIds().get("retry_then_escalation"));
        assertThat(retryEscalation.factors()).contains("MULTIPLE_PREVIOUS_ATTEMPTS", "ESCALATED_AWAITING_MANUAL_REVIEW");

        RevenueRiskResponse highValue = analyzeByExternalId(seedReport.demoTransactionIds().get("high_value_requires_approval"));
        assertThat(highValue.amountAtRisk()).isGreaterThanOrEqualTo(new BigDecimal("10000"));
        assertThat(highValue.factors()).contains("HIGH_TRANSACTION_VALUE");

        RevenueRiskResponse repeatedFailure = analyzeByExternalId(seedReport.demoTransactionIds().get("repeated_failure_stopped"));
        assertThat(repeatedFailure.factors()).contains("REPEATED_PAYMENT_FAILURE", "AUTOMATED_RECOVERY_STOPPED");

        RevenueRiskResponse successfulRecovery = analyzeByExternalId(seedReport.demoTransactionIds().get("successful_recovery"));
        assertThat(successfulRecovery.amountAtRisk()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(successfulRecovery.riskLevel()).isEqualTo(RiskLevel.LOW);

        log.info("Demo transaction risk profiles:");
        log.info("  demo-easy-recovery: riskScore={} recoveryProbability={} factors={}",
                easyRecovery.riskScore(), easyRecovery.recoveryProbability(), easyRecovery.factors());
        log.info("  demo-retry-escalation: riskScore={} recoveryProbability={} factors={}",
                retryEscalation.riskScore(), retryEscalation.recoveryProbability(), retryEscalation.factors());
        log.info("  demo-high-value: riskScore={} recoveryProbability={} amountAtRisk={} factors={}",
                highValue.riskScore(), highValue.recoveryProbability(), highValue.amountAtRisk(), highValue.factors());
        log.info("  demo-repeated-failure: riskScore={} recoveryProbability={} factors={}",
                repeatedFailure.riskScore(), repeatedFailure.recoveryProbability(), repeatedFailure.factors());
        log.info("  demo-successful-recovery: amountAtRisk={} riskLevel={}",
                successfulRecovery.amountAtRisk(), successfulRecovery.riskLevel());
    }

    private RevenueRiskResponse analyzeByExternalId(String externalId) {
        UUID transactionId = transactionRepository.findByExternalTransactionId(externalId).orElseThrow().getId();
        return revenueRiskService.analyzeTransaction(transactionId);
    }
}
