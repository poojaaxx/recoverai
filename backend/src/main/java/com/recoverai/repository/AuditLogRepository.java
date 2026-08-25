package com.recoverai.repository;

import com.recoverai.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Policy decision counts for observability ({@code ObservabilityService}).
     * {@code RECOVERY_POLICY_EVALUATED} is the single authoritative,
     * deduplicated audit event {@code RecoveryPolicyService} writes on
     * every code path that reaches a decision (standalone evaluation, the
     * AI agent, and the execution pipeline all call the same {@code
     * evaluate()}), so grouping by {@code decision} on that one event type
     * is a real count, not an approximation stitched from several places.
     */
    @Query("SELECT a.decision AS decision, COUNT(a) AS total FROM AuditLog a WHERE a.eventType = :eventType GROUP BY a.decision")
    List<DecisionCount> countGroupedByDecision(@Param("eventType") String eventType);

    interface DecisionCount {
        String getDecision();
        Long getTotal();
    }
}
