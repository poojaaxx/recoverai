package com.recoverai.repository;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.RiskLevel;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByExternalTransactionId(String externalTransactionId);

    Page<Transaction> findByMerchantId(UUID merchantId, Pageable pageable);

    List<Transaction> findByCustomerId(UUID customerId);

    List<Transaction> findByStatus(TransactionStatus status);

    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);

    long countByStatus(TransactionStatus status);

    /**
     * Fetch-joins {@code customer} so batch risk analysis (which reads
     * customer payment-history fields for every transaction) does not
     * trigger one lazy-load query per transaction.
     */
    @Query("SELECT t FROM Transaction t JOIN FETCH t.customer WHERE t.status IN :statuses")
    List<Transaction> findByStatusInWithCustomer(@Param("statuses") Collection<TransactionStatus> statuses);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t")
    BigDecimal sumAllTransactionValue();

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.status IN :statuses")
    BigDecimal sumAmountByStatusIn(@Param("statuses") Collection<TransactionStatus> statuses);

    /**
     * The general-purpose transaction dashboard's combinable filter/search
     * query (Phase 13). Every filter is optional — {@code (:param IS NULL
     * OR ...)} is the standard Spring Data idiom for building one static,
     * combinable JPQL query instead of a dynamic Specification. {@code r}
     * is an ad hoc left join to {@link com.recoverai.domain.RevenueRisk}
     * (one row per transaction, or none if never analyzed — see V8),
     * needed for the risk-level filter and available for risk-based
     * sorting via the caller's {@link Pageable}.
     * <p>
     * {@code recoveryAttemptStatus} matches a transaction whose <b>latest</b>
     * recovery attempt (highest {@code attemptNumber} — attempt numbers are
     * assigned sequentially per transaction by {@code
     * RecoveryExecutionService}, so this is deterministic even when two
     * attempts share the same {@code executedAt} timestamp) is in that
     * status — not merely "has any attempt ever in that status". A
     * transaction with attempt 1 = FAILED, attempt 2 = SUCCESS matches
     * {@code SUCCESS} and not {@code FAILED}. Older attempts remain fully
     * queryable via {@code GET /api/transactions/{id}/detail}, which returns
     * the complete history — this filter only narrows the list view.
     * <p>
     * {@code searchExternalIdLike} matches a case-insensitive substring of
     * {@code externalTransactionId}; {@code searchId} matches the
     * transaction's own id or its customer's id exactly (a client can
     * search by either without the query needing to guess which).
     */
    @Query("""
            SELECT t FROM Transaction t LEFT JOIN RevenueRisk r ON r.transaction = t
            WHERE (:status IS NULL OR t.status = :status)
              AND (:riskLevel IS NULL OR r.riskLevel = :riskLevel)
              AND (:failureCode IS NULL OR t.failureCode = :failureCode)
              AND (:paymentMethod IS NULL OR t.paymentMethod = :paymentMethod)
              AND (:minAmount IS NULL OR t.amount >= :minAmount)
              AND (:maxAmount IS NULL OR t.amount <= :maxAmount)
              AND (
                    (:searchExternalIdLike IS NULL AND :searchId IS NULL)
                    OR (:searchExternalIdLike IS NOT NULL AND LOWER(t.externalTransactionId) LIKE :searchExternalIdLike)
                    OR (:searchId IS NOT NULL AND (t.id = :searchId OR t.customer.id = :searchId))
              )
              AND (:atRiskOnly = FALSE OR (r.amountAtRisk IS NOT NULL AND r.amountAtRisk > 0))
              AND (:recoveredOnly = FALSE OR t.status = com.recoverai.domain.TransactionStatus.RECOVERED)
              AND (:recoveryAttemptStatus IS NULL OR EXISTS (
                    SELECT 1 FROM RecoveryAttempt ra WHERE ra.transaction = t
                      AND ra.status = :recoveryAttemptStatus
                      AND ra.attemptNumber = (SELECT MAX(ra2.attemptNumber) FROM RecoveryAttempt ra2 WHERE ra2.transaction = t)))
            """)
    Page<Transaction> search(
            @Param("status") TransactionStatus status,
            @Param("riskLevel") RiskLevel riskLevel,
            @Param("failureCode") String failureCode,
            @Param("paymentMethod") PaymentMethod paymentMethod,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("searchExternalIdLike") String searchExternalIdLike,
            @Param("searchId") UUID searchId,
            @Param("atRiskOnly") boolean atRiskOnly,
            @Param("recoveredOnly") boolean recoveredOnly,
            @Param("recoveryAttemptStatus") RecoveryAttemptStatus recoveryAttemptStatus,
            Pageable pageable);
}
