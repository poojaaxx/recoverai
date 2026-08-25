package com.recoverai.transaction;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.AuditTimelineEntryResponse;
import com.recoverai.dto.RecoveryAttemptSummaryResponse;
import com.recoverai.dto.RevenueRiskResponse;
import com.recoverai.dto.TransactionDetailResponse;
import com.recoverai.dto.TransactionFullDetailResponse;
import com.recoverai.dto.TransactionListItemResponse;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.risk.TransactionNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * General-purpose transaction dashboard (Phase 13) - a pure read/
 * aggregation layer, same discipline as {@code RecoveryDemoService}: no
 * risk/AI/policy/payment decision logic of its own, only real persisted
 * facts, shaped for display. Works over every transaction in the
 * database, not the 5 curated demo scenarios.
 */
@Service
public class TransactionDashboardService {

    private final TransactionRepository transactionRepository;
    private final RevenueRiskRepository revenueRiskRepository;
    private final RecoveryAttemptRepository recoveryAttemptRepository;
    private final AuditLogRepository auditLogRepository;

    public TransactionDashboardService(TransactionRepository transactionRepository,
                                        RevenueRiskRepository revenueRiskRepository,
                                        RecoveryAttemptRepository recoveryAttemptRepository,
                                        AuditLogRepository auditLogRepository) {
        this.transactionRepository = transactionRepository;
        this.revenueRiskRepository = revenueRiskRepository;
        this.recoveryAttemptRepository = recoveryAttemptRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<TransactionListItemResponse> search(
            TransactionStatus status, RiskLevel riskLevel, String failureCode, PaymentMethod paymentMethod,
            BigDecimal minAmount, BigDecimal maxAmount, String search, boolean atRiskOnly, boolean recoveredOnly,
            RecoveryAttemptStatus recoveryAttemptStatus, TransactionSort sort, Pageable basePageable) {

        Pageable pageable = PageRequest.of(basePageable.getPageNumber(), basePageable.getPageSize(),
                (sort == null ? TransactionSort.NEWEST : sort).toSort());

        String searchLike = blankToNull(search) == null ? null : "%" + search.trim().toLowerCase() + "%";
        UUID searchId = parseUuidOrNull(search);

        Page<Transaction> page = transactionRepository.search(
                status, riskLevel, blankToNull(failureCode), paymentMethod, minAmount, maxAmount,
                searchLike, searchId, atRiskOnly, recoveredOnly, recoveryAttemptStatus, pageable);

        List<UUID> ids = page.getContent().stream().map(Transaction::getId).toList();
        Map<UUID, RevenueRisk> riskById = revenueRiskRepository.findByTransactionIdIn(ids).stream()
                .collect(Collectors.toMap(r -> r.getTransaction().getId(), r -> r));
        Map<UUID, RecoveryAttempt> latestAttemptById = recoveryAttemptRepository.findByTransactionIdIn(ids).stream()
                .collect(Collectors.groupingBy(a -> a.getTransaction().getId(),
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparingInt(RecoveryAttempt::getAttemptNumber)),
                                opt -> opt.orElse(null))));

        List<TransactionListItemResponse> items = page.getContent().stream()
                .map(t -> toListItem(t, riskById.get(t.getId()), latestAttemptById.get(t.getId())))
                .toList();
        return new PageImpl<>(items, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public TransactionFullDetailResponse getFullDetail(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        RevenueRisk risk = revenueRiskRepository.findByTransactionId(transactionId).orElse(null);
        List<RecoveryAttemptSummaryResponse> attempts = recoveryAttemptRepository
                .findByTransactionIdOrderByAttemptNumberAsc(transactionId).stream()
                .map(RecoveryAttemptSummaryResponse::from)
                .toList();
        List<AuditTimelineEntryResponse> audit = auditLogRepository
                .findByTransactionIdOrderByTimestampAsc(transactionId).stream()
                .map(AuditTimelineEntryResponse::from)
                .toList();

        return new TransactionFullDetailResponse(
                TransactionDetailResponse.from(transaction),
                transaction.getCustomer().getSuccessfulPaymentCount(),
                transaction.getCustomer().getFailedPaymentCount(),
                transaction.getCustomer().getTotalHistoricalValue(),
                risk == null ? null : RevenueRiskResponse.from(risk),
                attempts,
                audit
        );
    }

    private static TransactionListItemResponse toListItem(Transaction t, RevenueRisk risk, RecoveryAttempt latest) {
        return new TransactionListItemResponse(
                t.getId(), t.getExternalTransactionId(), t.getAmount(), t.getCurrency(),
                t.getStatus(), t.getPaymentMethod(), t.getFailureCode(), t.getAttemptCount(), t.getCreatedAt(),
                risk == null ? null : risk.getRiskScore(),
                risk == null ? null : risk.getRiskLevel(),
                risk == null ? null : risk.getRecoveryProbability(),
                risk == null ? null : risk.getAmountAtRisk(),
                latest == null ? null : latest.getAction(),
                latest == null ? null : latest.getStatus(),
                latest == null ? null : latest.getExecutedAt()
        );
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static UUID parseUuidOrNull(String s) {
        if (blankToNull(s) == null) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
