package com.recoverai.repository;

import com.recoverai.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByTransactionIdOrderByTimestampAsc(UUID transactionId);

    /**
     * Used by {@code RecoveryPolicyService} to avoid writing a new audit
     * row for a policy re-evaluation that produced the same decision as
     * last time - see its javadoc for the audit-noise rationale.
     */
    Optional<AuditLog> findTopByTransactionIdAndEventTypeOrderByTimestampDesc(UUID transactionId, String eventType);
}
