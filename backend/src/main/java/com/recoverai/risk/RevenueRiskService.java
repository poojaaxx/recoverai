package com.recoverai.risk;

import com.recoverai.config.RevenueRiskProperties;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.BatchRiskAnalysisResponse;
import com.recoverai.dto.RevenueRiskMetricsResponse;
import com.recoverai.dto.RevenueRiskResponse;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, explainable revenue-at-risk scoring — the Phase 3 engine.
 * <p>
 * Given a transaction, its customer's payment history, and its recovery
 * attempt history, this produces {@code amountAtRisk} (a real monetary
 * figure), {@code riskScore} (0-100, how much/urgent the exposure is) and
 * {@code recoveryProbability} (0.0-1.0, how likely recovery is if
 * attempted) as two DELIBERATELY DISTINCT metrics — a ₹47,500 temporary
 * failure from a strong-history customer can be simultaneously
 * high-risk-score (large exposure) and high-recovery-probability (likely
 * to come back). Neither number is a machine-learning prediction; both are
 * a fixed, versioned formula over {@link RevenueRiskProperties} weights,
 * so the same input always produces the same output.
 * <p>
 * This service does not decide or execute any recovery action — that is
 * the AI agent (Phase 5) and the safety policy engine (Phase 4). It does
 * not call any AI/LLM provider.
 */
@Service
@RequiredArgsConstructor
public class RevenueRiskService {

    /** Transactions representing unresolved revenue exposure - the batch analysis target. */
    private static final Set<TransactionStatus> AT_RISK_STATUSES =
            EnumSet.of(TransactionStatus.FAILED, TransactionStatus.PENDING, TransactionStatus.ABANDONED,
                    TransactionStatus.ESCALATED, TransactionStatus.STOPPED);

    /** Transactions where the revenue question is already settled - never treated as at-risk. */
    private static final Set<TransactionStatus> RESOLVED_STATUSES =
            EnumSet.of(TransactionStatus.SUCCESS, TransactionStatus.RECOVERED);

    private static final int WORKING_SCALE = 6;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TransactionRepository transactionRepository;
    private final RevenueRiskRepository revenueRiskRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final RevenueRiskProperties properties;

    @Transactional
    public RevenueRiskResponse analyzeTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        long failedRecoveryAttempts = recoveryAttemptRepository
                .countByTransactionIdAndStatus(transactionId, RecoveryAttemptStatus.FAILED);
        RevenueRisk saved = persist(transaction, compute(transaction, failedRecoveryAttempts));
        return RevenueRiskResponse.from(saved);
    }

    @Transactional
    public BatchRiskAnalysisResponse analyzeAllAtRisk() {
        List<Transaction> eligible = transactionRepository.findByStatusInWithCustomer(AT_RISK_STATUSES);

        Map<UUID, Long> failedAttemptCounts = new LinkedHashMap<>();
        if (!eligible.isEmpty()) {
            List<UUID> ids = eligible.stream().map(Transaction::getId).toList();
            recoveryAttemptRepository.countFailedByTransactionIds(ids)
                    .forEach(row -> failedAttemptCounts.put(row.getTransactionId(), row.getFailedCount()));
        }

        for (Transaction transaction : eligible) {
            long failedRecoveryAttempts = failedAttemptCounts.getOrDefault(transaction.getId(), 0L);
            persist(transaction, compute(transaction, failedRecoveryAttempts));
        }

        correctStaleResolvedRiskRows();

        return new BatchRiskAnalysisResponse(eligible.size(), getMetrics());
    }

    /**
     * A transaction can carry a {@code RevenueRisk} row that predates its
     * current RESOLVED status - e.g. the Phase 2 seed data writes a
     * (non-zero-amountAtRisk) seed-heuristic risk row for RECOVERED
     * transactions, since at seed-authoring time that row represents "what
     * the risk looked like before recovery succeeded." Left uncorrected,
     * that stale row would make already-collected revenue keep showing up
     * as currently at-risk in the aggregate metrics. This sweep re-runs
     * {@link #compute} (which zeroes out RESOLVED transactions) over any
     * RESOLVED transaction that already has a risk row, without creating
     * new rows for resolved transactions that never had one.
     */
    private void correctStaleResolvedRiskRows() {
        List<Transaction> resolved = transactionRepository.findByStatusInWithCustomer(RESOLVED_STATUSES);
        for (Transaction transaction : resolved) {
            if (revenueRiskRepository.findByTransactionId(transaction.getId()).isPresent()) {
                persist(transaction, compute(transaction, 0));
            }
        }
    }

    @Transactional(readOnly = true)
    public RevenueRiskMetricsResponse getMetrics() {
        long totalTransactions = transactionRepository.count();
        long atRiskTransactions = revenueRiskRepository.countAtRisk();
        BigDecimal totalTransactionValue = transactionRepository.sumAllTransactionValue();
        BigDecimal totalRevenueCollected = transactionRepository.sumAmountByStatusIn(RESOLVED_STATUSES);
        BigDecimal revenueAtRisk = revenueRiskRepository.sumAmountAtRisk();
        BigDecimal highRiskRevenue = revenueRiskRepository.sumAmountAtRiskByRiskLevel(RiskLevel.HIGH);
        BigDecimal criticalRiskRevenue = revenueRiskRepository.sumAmountAtRiskByRiskLevel(RiskLevel.CRITICAL);
        BigDecimal averageRecoveryProbability = revenueRiskRepository.averageRecoveryProbability()
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal potentiallyRecoverableRevenue = revenueRiskRepository.sumPotentialRecoveryValue()
                .setScale(2, RoundingMode.HALF_UP);

        return new RevenueRiskMetricsResponse(
                totalTransactions, atRiskTransactions, totalTransactionValue, totalRevenueCollected,
                revenueAtRisk, highRiskRevenue, criticalRiskRevenue, averageRecoveryProbability,
                potentiallyRecoverableRevenue
        );
    }

    // ---------------------------------------------------------------- persistence (idempotent upsert)

    private RevenueRisk persist(Transaction transaction, RiskComputation computation) {
        RevenueRisk risk = revenueRiskRepository.findByTransactionId(transaction.getId())
                .orElseGet(() -> RevenueRisk.builder().transaction(transaction).build());
        risk.setAmountAtRisk(computation.amountAtRisk());
        risk.setRiskScore(computation.riskScore());
        risk.setRiskLevel(computation.riskLevel());
        risk.setRecoveryProbability(computation.recoveryProbability());
        risk.setFactors(computation.factors());
        risk.setReason(computation.reason());
        risk.setDetectedAt(Instant.now());
        return revenueRiskRepository.save(risk);
    }

    // ---------------------------------------------------------------- the scoring model

    private record RiskComputation(
            BigDecimal amountAtRisk,
            BigDecimal riskScore,
            RiskLevel riskLevel,
            BigDecimal recoveryProbability,
            List<String> factors,
            String reason
    ) {
    }

    private RiskComputation compute(Transaction transaction, long failedRecoveryAttempts) {
        if (RESOLVED_STATUSES.contains(transaction.getStatus())) {
            return resolvedComputation(transaction);
        }

        Customer customer = transaction.getCustomer();
        FailureCategory category = resolveFailureCategory(transaction);
        BigDecimal baseRecoveryWeight = baseRecoveryWeight(transaction, category);

        BigDecimal historyScore = historyScore(customer);
        BigDecimal amountFactor = amountFactor(transaction.getAmount());
        BigDecimal failureSeverity = ONE.subtract(baseRecoveryWeight);

        long attemptUnits = Math.max(transaction.getAttemptCount() - 1, 0) + failedRecoveryAttempts;
        BigDecimal attemptUnitsDecimal = BigDecimal.valueOf(attemptUnits);
        BigDecimal attemptUrgency = min(ONE, attemptUnitsDecimal.multiply(properties.getAttempts().getRiskUrgencyPerUnit()));
        BigDecimal attemptPenalty = attemptUnitsDecimal.multiply(properties.getAttempts().getProbabilityPenaltyPerUnit());

        BigDecimal riskScoreFraction = properties.getWeights().getAmount().multiply(amountFactor)
                .add(properties.getWeights().getFailure().multiply(failureSeverity))
                .add(properties.getWeights().getHistory().multiply(ONE.subtract(historyScore)))
                .add(properties.getWeights().getAttempts().multiply(attemptUrgency));
        BigDecimal riskScore = clamp(riskScoreFraction.multiply(HUNDRED), ZERO, HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
        RiskLevel riskLevel = properties.classify(riskScore);

        BigDecimal historyAdjustment = historyScore.subtract(HALF)
                .multiply(properties.getHistory().getProbabilityAdjustmentWeight());
        BigDecimal recoveryProbability = baseRecoveryWeight.add(historyAdjustment).subtract(attemptPenalty);
        boolean terminal = transaction.getStatus() == TransactionStatus.ESCALATED
                || transaction.getStatus() == TransactionStatus.STOPPED;
        if (terminal) {
            recoveryProbability = recoveryProbability.multiply(properties.getTerminalStateRecoveryFactor());
        }
        recoveryProbability = clamp(recoveryProbability, ZERO, ONE).setScale(4, RoundingMode.HALF_UP);

        BigDecimal amountAtRisk = transaction.getAmount().setScale(2, RoundingMode.HALF_UP);

        List<String> factors = buildFactors(transaction, category, historyScore, amountFactor,
                attemptUnits, failedRecoveryAttempts);
        String reason = buildReason(transaction, riskLevel, recoveryProbability, factors);

        return new RiskComputation(amountAtRisk, riskScore, riskLevel, recoveryProbability, factors, reason);
    }

    private RiskComputation resolvedComputation(Transaction transaction) {
        boolean success = transaction.getStatus() == TransactionStatus.SUCCESS;
        String reason = success
                ? "Transaction completed successfully; no revenue is at risk."
                : "Transaction was already recovered; revenue has been collected.";
        List<String> factors = List.of(success ? "TRANSACTION_SUCCESSFUL" : "REVENUE_RECOVERED");
        return new RiskComputation(
                ZERO.setScale(2, RoundingMode.HALF_UP),
                ZERO.setScale(2, RoundingMode.HALF_UP),
                RiskLevel.LOW,
                ONE.setScale(4, RoundingMode.HALF_UP),
                factors,
                reason
        );
    }

    FailureCategory resolveFailureCategory(Transaction transaction) {
        String code = transaction.getFailureCode();
        if (code == null) {
            return FailureCategory.UNKNOWN;
        }
        try {
            return FailureCategory.valueOf(code);
        } catch (IllegalArgumentException e) {
            // A real gateway's failure vocabulary is outside this app's control (see FailureCategory javadoc).
            return FailureCategory.UNKNOWN;
        }
    }

    private BigDecimal baseRecoveryWeight(Transaction transaction, FailureCategory category) {
        return switch (transaction.getStatus()) {
            case PENDING -> properties.getPendingBaselineRecoveryWeight();
            case ABANDONED -> properties.getAbandonedBaselineRecoveryWeight();
            default -> properties.getFailureCategoryRecoveryWeights()
                    .getOrDefault(category, properties.getFailureCategoryRecoveryWeights().get(FailureCategory.UNKNOWN));
        };
    }

    /** Bayesian-smoothed success ratio in (0,1) - a brand-new customer with no history lands at exactly 0.5 (neutral). */
    BigDecimal historyScore(Customer customer) {
        BigDecimal success = BigDecimal.valueOf(customer.getSuccessfulPaymentCount());
        BigDecimal failed = BigDecimal.valueOf(customer.getFailedPaymentCount());
        return success.add(ONE).divide(success.add(failed).add(BigDecimal.valueOf(2)), WORKING_SCALE, RoundingMode.HALF_UP);
    }

    BigDecimal amountFactor(BigDecimal amount) {
        RevenueRiskProperties.AmountThresholds t = properties.getAmountThresholds();
        RevenueRiskProperties.AmountFactors f = properties.getAmountFactors();
        if (amount.compareTo(t.getLow()) < 0) return f.getLow();
        if (amount.compareTo(t.getMid()) < 0) return f.getMid();
        if (amount.compareTo(t.getHigh()) < 0) return f.getHigh();
        return f.getVeryHigh();
    }

    private List<String> buildFactors(Transaction transaction, FailureCategory category, BigDecimal historyScore,
                                       BigDecimal amountFactor, long attemptUnits, long failedRecoveryAttempts) {
        Set<String> factors = new LinkedHashSet<>();

        factors.add(categoryFactorTag(transaction, category));
        factors.add("AMOUNT_AT_RISK");

        if (amountFactor.compareTo(properties.getAmountFactors().getMid()) >= 0) {
            factors.add("HIGH_TRANSACTION_VALUE");
        }
        if (historyScore.compareTo(properties.getHistory().getStrongThreshold()) >= 0) {
            factors.add("STRONG_CUSTOMER_HISTORY");
        } else if (historyScore.compareTo(properties.getHistory().getWeakThreshold()) <= 0) {
            factors.add("WEAK_CUSTOMER_HISTORY");
        }
        if (failedRecoveryAttempts >= 1 || transaction.getCustomer().getFailedPaymentCount() >= 3) {
            factors.add("REPEATED_PAYMENT_FAILURE");
        }
        if (attemptUnits >= 2) {
            factors.add("MULTIPLE_PREVIOUS_ATTEMPTS");
        }
        if (category == FailureCategory.NETWORK_ERROR) {
            factors.add("NETWORK_RELATED_FAILURE");
        }
        if (category == FailureCategory.UNKNOWN && transaction.getStatus() == TransactionStatus.FAILED) {
            factors.add("UNKNOWN_FAILURE_REASON");
        }
        if (transaction.getStatus() == TransactionStatus.ESCALATED) {
            factors.add("ESCALATED_AWAITING_MANUAL_REVIEW");
        }
        if (transaction.getStatus() == TransactionStatus.STOPPED) {
            factors.add("AUTOMATED_RECOVERY_STOPPED");
        }

        return new ArrayList<>(factors);
    }

    private String buildReason(Transaction transaction, RiskLevel riskLevel, BigDecimal recoveryProbability,
                                List<String> factors) {
        boolean easyFailure = factors.contains("TEMPORARY_FAILURE") || factors.contains("NETWORK_RELATED_FAILURE");
        boolean strongHistory = factors.contains("STRONG_CUSTOMER_HISTORY");
        boolean repeatedFailure = factors.contains("REPEATED_PAYMENT_FAILURE") || factors.contains("MULTIPLE_PREVIOUS_ATTEMPTS");
        boolean highValue = factors.contains("HIGH_TRANSACTION_VALUE");
        boolean goodOdds = recoveryProbability.compareTo(new BigDecimal("0.60")) >= 0;
        boolean fairOdds = recoveryProbability.compareTo(new BigDecimal("0.35")) >= 0;

        if (transaction.getStatus() == TransactionStatus.ESCALATED) {
            return "Automated recovery attempts were exhausted; this transaction is awaiting manual review.";
        }
        if (transaction.getStatus() == TransactionStatus.STOPPED) {
            return "Automated recovery was safely stopped after repeated failures; revenue remains uncollected.";
        }
        if (transaction.getStatus() == TransactionStatus.PENDING) {
            return "Payment is pending confirmation; outcome not yet determined.";
        }
        if (transaction.getStatus() == TransactionStatus.ABANDONED) {
            return "Checkout was abandoned before payment completion; recovery requires re-engagement.";
        }
        if (easyFailure && strongHistory && goodOdds && !repeatedFailure) {
            return "Temporary failure with strong customer history creates a high recovery opportunity.";
        }
        if (repeatedFailure && fairOdds) {
            return "Repeated payment failures reduce recovery confidence despite the transaction being recoverable.";
        }
        if (highValue && (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL)) {
            return "High-value failed transaction creates significant revenue exposure.";
        }
        return "%s failure classified as %s risk with an estimated %.0f%% recovery probability."
                .formatted(categoryLabel(transaction), riskLevel, recoveryProbability.multiply(HUNDRED));
    }

    private String categoryFactorTag(Transaction transaction, FailureCategory category) {
        return switch (transaction.getStatus()) {
            case PENDING -> "PAYMENT_PENDING_CONFIRMATION";
            case ABANDONED -> "CHECKOUT_ABANDONED";
            default -> category.name();
        };
    }

    private String categoryLabel(Transaction transaction) {
        return switch (transaction.getStatus()) {
            case PENDING -> "Pending";
            case ABANDONED -> "Abandoned checkout";
            default -> resolveFailureCategory(transaction).name();
        };
    }

    // ---------------------------------------------------------------- BigDecimal helpers

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
