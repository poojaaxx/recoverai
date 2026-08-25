package com.recoverai.repository;

import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryAttemptRepository extends JpaRepository<RecoveryAttempt, UUID> {

    List<RecoveryAttempt> findByTransactionIdOrderByAttemptNumberAsc(UUID transactionId);

    /** Batched lookup for a page of transactions (dashboard list view) - the caller picks the highest {@code attemptNumber} per transaction to find "the latest attempt". */
    List<RecoveryAttempt> findByTransactionIdIn(Collection<UUID> transactionIds);

    long countByTransactionIdAndStatus(UUID transactionId, RecoveryAttemptStatus status);

    /** Used by {@code RecoveryExecutionService} to detect a replayed/duplicate execution request before ever calling the payment gateway. */
    Optional<RecoveryAttempt> findByIdempotencyKey(String idempotencyKey);

    /** Used by {@code PaymentConfirmationService} to correlate an inbound webhook back to the attempt that created the payment link/order it refers to. */
    Optional<RecoveryAttempt> findByProviderReference(String providerReference);

    long countByPaymentConfirmationStatus(PaymentConfirmationStatus status);

    long countByStatus(RecoveryAttemptStatus status);

    @Query("SELECT COALESCE(SUM(ra.confirmedAmount), 0) FROM RecoveryAttempt ra WHERE ra.paymentConfirmationStatus = com.recoverai.domain.PaymentConfirmationStatus.CONFIRMED")
    BigDecimal sumConfirmedAmount();

    /** Amount already sent to a provider (execution SUCCESS) but not yet proven paid by a webhook - the "money in flight" figure for {@code RecoveryMetricsResponse}. */
    @Query("""
            SELECT COALESCE(SUM(ra.amount), 0) FROM RecoveryAttempt ra
            WHERE ra.status = com.recoverai.domain.RecoveryAttemptStatus.SUCCESS
              AND ra.paymentConfirmationStatus = com.recoverai.domain.PaymentConfirmationStatus.NOT_CONFIRMED
            """)
    BigDecimal sumPendingConfirmationAmount();

    /**
     * Batch equivalent of {@link #countByTransactionIdAndStatus} — one
     * grouped query instead of one query per transaction, used by {@code
     * RevenueRiskService}'s batch analysis to avoid N+1.
     */
    @Query("""
            SELECT ra.transaction.id AS transactionId, COUNT(ra) AS failedCount
            FROM RecoveryAttempt ra
            WHERE ra.status = com.recoverai.domain.RecoveryAttemptStatus.FAILED
              AND ra.transaction.id IN :transactionIds
            GROUP BY ra.transaction.id
            """)
    List<FailedAttemptCount> countFailedByTransactionIds(@Param("transactionIds") Collection<UUID> transactionIds);

    interface FailedAttemptCount {
        UUID getTransactionId();
        Long getFailedCount();
    }
}
