package com.recoverai.repository;

import com.recoverai.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByTransactionIdOrderByTimestampAsc(UUID transactionId);

    /**
     * P1.4 - the portfolio-wide, filterable, paginated audit feed ({@code
     * GET /api/audit}). Every filter optional, same combinable-JPQL idiom
     * {@code TransactionRepository.search} already uses rather than a
     * dynamic Specification. Ordering (newest-first by default) comes from
     * the caller's {@link Pageable}, not hardcoded here.
     * <p>
     * {@code from}/{@code to} are always non-null by the time they reach
     * this query (see {@code AuditController.search}, which substitutes
     * sentinel min/max bounds for an omitted date range) - real PostgreSQL
     * cannot determine the parameter type for {@code (:from IS NULL OR
     * a.timestamp >= :from)} the way it can for the string/UUID filters
     * above (confirmed via {@code AuditLogRepositoryPostgresTest} against a
     * real embedded Postgres instance: {@code ERROR: could not determine
     * data type of parameter}), so a plain range comparison is used instead
     * of the null-guarded form for these two.
     */
    @Query("""
            SELECT a FROM AuditLog a
            WHERE (:eventType IS NULL OR a.eventType = :eventType)
              AND (:actor IS NULL OR a.actor = :actor)
              AND (:transactionId IS NULL OR a.transaction.id = :transactionId)
              AND a.timestamp >= :from
              AND a.timestamp <= :to
            """)
    Page<AuditLog> search(@Param("eventType") String eventType, @Param("actor") String actor,
                           @Param("transactionId") UUID transactionId, @Param("from") Instant from,
                           @Param("to") Instant to, Pageable pageable);

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
