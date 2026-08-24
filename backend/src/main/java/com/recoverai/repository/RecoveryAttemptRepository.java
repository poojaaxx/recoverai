package com.recoverai.repository;

import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecoveryAttemptRepository extends JpaRepository<RecoveryAttempt, UUID> {

    List<RecoveryAttempt> findByTransactionIdOrderByAttemptNumberAsc(UUID transactionId);

    long countByTransactionIdAndStatus(UUID transactionId, RecoveryAttemptStatus status);

    /** Used by {@code RecoveryExecutionService} to detect a replayed/duplicate execution request before ever calling the payment gateway. */
    Optional<RecoveryAttempt> findByIdempotencyKey(String idempotencyKey);

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
