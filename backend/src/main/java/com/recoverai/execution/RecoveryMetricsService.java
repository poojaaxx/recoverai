package com.recoverai.execution;

import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RecoveryMetricsResponse;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.RevenueRiskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Portfolio-level recovery metrics (Phase 12) - a pure read/aggregation
 * layer over {@link RecoveryAttemptRepository} and {@link
 * RevenueRiskService}, with no risk/AI/policy/payment decision logic of
 * its own. See {@link RecoveryMetricsResponse}'s javadoc for the
 * "confirmed revenue is the only real figure" guarantee this preserves.
 */
@Service
public class RecoveryMetricsService {

    private static final int RATE_SCALE = 4;

    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final TransactionRepository transactionRepository;
    private final RevenueRiskService revenueRiskService;

    public RecoveryMetricsService(RecoveryAttemptRepository recoveryAttemptRepository,
                                   TransactionRepository transactionRepository,
                                   RevenueRiskService revenueRiskService) {
        this.recoveryAttemptRepository = recoveryAttemptRepository;
        this.transactionRepository = transactionRepository;
        this.revenueRiskService = revenueRiskService;
    }

    @Transactional(readOnly = true)
    public RecoveryMetricsResponse getMetrics() {
        var riskMetrics = revenueRiskService.getMetrics();

        long totalAttempts = recoveryAttemptRepository.count();
        long successfulExecutions = recoveryAttemptRepository.countByStatusAndProviderIsNotNull(RecoveryAttemptStatus.SUCCESS);
        long confirmedRecoveries = recoveryAttemptRepository.countByPaymentConfirmationStatus(PaymentConfirmationStatus.CONFIRMED);
        BigDecimal confirmedRevenue = zeroIfNull(recoveryAttemptRepository.sumConfirmedAmount());
        BigDecimal pendingConfirmation = zeroIfNull(recoveryAttemptRepository.sumPendingConfirmationAmount());
        BigDecimal remainingAtRisk = riskMetrics.revenueAtRisk().subtract(confirmedRevenue).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        return new RecoveryMetricsResponse(
                riskMetrics.revenueAtRisk(),
                riskMetrics.potentiallyRecoverableRevenue(),
                totalAttempts,
                successfulExecutions,
                confirmedRecoveries,
                confirmedRevenue,
                rate(confirmedRecoveries, totalAttempts),
                rate(successfulExecutions, totalAttempts),
                rate(confirmedRecoveries, successfulExecutions),
                pendingConfirmation,
                remainingAtRisk,
                transactionRepository.countByStatus(TransactionStatus.RECOVERED),
                transactionRepository.countByStatus(TransactionStatus.ESCALATED),
                transactionRepository.countByStatus(TransactionStatus.STOPPED)
        );
    }

    /** {@code 0} whenever the denominator is {@code 0} - never a division-by-zero exception, never a fabricated rate. */
    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value;
    }
}
