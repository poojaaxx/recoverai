package com.recoverai.agent;

import com.recoverai.dto.RecoveryAgentBatchResponse;
import com.recoverai.risk.RevenueRiskService;
import com.recoverai.seed.DemoDataSeeder;
import com.recoverai.seed.SeedReport;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batch AI recommendation statistics over the full seed dataset - counts
 * and confidence only, never a claim of money recovered (no execution
 * happens in this phase).
 */
@SpringBootTest
@ActiveProfiles("test")
class RecoveryAgentBatchTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryAgentBatchTest.class);

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RevenueRiskService revenueRiskService;
    @Autowired
    private RecoveryAgentService recoveryAgentService;

    @Test
    void batchEvaluationOverSeedDataset_producesConsistentAggregateStatistics() {
        SeedReport seedReport = seeder.seed();
        revenueRiskService.analyzeAllAtRisk();

        long expectedEligible = seedReport.countsByStatus().get("FAILED")
                + seedReport.countsByStatus().get("PENDING")
                + seedReport.countsByStatus().get("ABANDONED")
                + seedReport.countsByStatus().get("ESCALATED")
                + seedReport.countsByStatus().get("STOPPED");

        RecoveryAgentBatchResponse response = recoveryAgentService.evaluateAll();

        log.info("Batch AI recommendation statistics over {} eligible transactions:", response.transactionsEvaluated());
        log.info("  Recommendation counts by action: {}", response.recommendationCountByAction());
        log.info("  Policy decision counts: {}", response.countByPolicyDecision());
        log.info("  Average confidence: {}", response.averageConfidence());
        log.info("  Provider failures: {}, malformed outputs: {}", response.providerFailures(), response.malformedOutputs());

        assertThat(response.transactionsEvaluated()).isEqualTo(expectedEligible);

        long actionTotal = response.recommendationCountByAction().values().stream().mapToLong(Long::longValue).sum();
        assertThat(actionTotal).isEqualTo(expectedEligible);

        long decisionTotal = response.countByPolicyDecision().values().stream().mapToLong(Long::longValue).sum();
        assertThat(decisionTotal).isEqualTo(expectedEligible);

        assertThat(response.averageConfidence())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.ONE);

        // The mock provider never fails and never produces malformed output.
        assertThat(response.providerFailures()).isZero();
        assertThat(response.malformedOutputs()).isZero();
    }

    @Test
    void batchEvaluation_isDeterministic_acrossRuns() {
        seeder.seed();
        revenueRiskService.analyzeAllAtRisk();

        RecoveryAgentBatchResponse first = recoveryAgentService.evaluateAll();
        RecoveryAgentBatchResponse second = recoveryAgentService.evaluateAll();

        assertThat(second.transactionsEvaluated()).isEqualTo(first.transactionsEvaluated());
        assertThat(second.recommendationCountByAction()).isEqualTo(first.recommendationCountByAction());
        assertThat(second.countByPolicyDecision()).isEqualTo(first.countByPolicyDecision());
        assertThat(second.averageConfidence()).isEqualByComparingTo(first.averageConfidence());
    }
}
