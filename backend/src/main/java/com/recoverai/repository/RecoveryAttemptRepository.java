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

    /**
     * Same as {@link #countByStatus} but restricted to rows a real {@link
     * com.recoverai.payment.PaymentGateway} call actually produced ({@code
     * provider IS NOT NULL}) - this is the "provider executions" figure.
     * {@link #countByStatus} alone would also count non-payment recorded
     * actions (e.g. {@code SEND_RECOVERY_REMINDER}, which is never routed
     * through a gateway and always has {@code provider=null}), which must
     * never be counted as a provider execution.
     */
    long countByStatusAndProviderIsNotNull(RecoveryAttemptStatus status);

    @Query("SELECT COALESCE(SUM(ra.confirmedAmount), 0) FROM RecoveryAttempt ra WHERE ra.paymentConfirmationStatus = com.recoverai.domain.PaymentConfirmationStatus.CONFIRMED")
    BigDecimal sumConfirmedAmount();

    /** Phase 14 metrics - distinct customers whose transactions have had at least one recovery attempt, i.e. customers the recovery system has actually processed (not merely "at risk"). */
    @Query("SELECT COUNT(DISTINCT ra.transaction.customer.id) FROM RecoveryAttempt ra")
    long countDistinctCustomersWithAttempts();

    /** Amount already sent to a provider (execution SUCCESS) but not yet proven paid by a webhook - the "money in flight" figure for {@code RecoveryMetricsResponse}. Restricted to real gateway calls ({@code provider IS NOT NULL}) so a recorded non-payment action (e.g. a reminder) never inflates this with its transaction's full amount. */
    @Query("""
            SELECT COALESCE(SUM(ra.amount), 0) FROM RecoveryAttempt ra
            WHERE ra.status = com.recoverai.domain.RecoveryAttemptStatus.SUCCESS
              AND ra.paymentConfirmationStatus = com.recoverai.domain.PaymentConfirmationStatus.NOT_CONFIRMED
              AND ra.provider IS NOT NULL
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

    /**
     * Provider observability ({@code ObservabilityService}). A {@link
     * RecoveryAttempt} row with a non-null {@code provider} exists exactly
     * when {@code RecoveryExecutionService} actually called {@code
     * PaymentGateway.execute()} - so grouping these by provider and status
     * is a genuine count of real provider calls and their outcomes,
     * without adding any new instrumentation inside the gateway
     * abstraction itself (which stays a pure execution boundary - see
     * docs/ARCHITECTURE.md "Razorpay Integration / Payment Adapter").
     */
    @Query("""
            SELECT ra.provider AS provider, ra.status AS status, COUNT(ra) AS total
            FROM RecoveryAttempt ra WHERE ra.provider IS NOT NULL
            GROUP BY ra.provider, ra.status
            """)
    List<ProviderStatusCount> countGroupedByProviderAndStatus();

    interface ProviderStatusCount {
        String getProvider();
        RecoveryAttemptStatus getStatus();
        Long getTotal();
    }

    /**
     * Eligible attempts for the judge-safe test-confirmation demo path
     * (P0.4, {@code DemoConfirmationService}): a real mock-provider
     * execution that succeeded, has a provider reference to correlate a
     * webhook against, and has not already been confirmed/rejected.
     * Restricted to {@code provider = 'mock'} specifically (never {@code
     * 'razorpay'}) so this path can never be used to fabricate a
     * confirmation for an attempt that went through a real gateway call.
     */
    @Query("""
            SELECT ra FROM RecoveryAttempt ra
            WHERE ra.transaction.id = :transactionId
              AND ra.status = com.recoverai.domain.RecoveryAttemptStatus.SUCCESS
              AND ra.provider = 'mock'
              AND ra.providerReference IS NOT NULL
              AND ra.paymentConfirmationStatus = com.recoverai.domain.PaymentConfirmationStatus.NOT_CONFIRMED
            ORDER BY ra.attemptNumber DESC
            """)
    List<RecoveryAttempt> findEligibleForTestConfirmation(@Param("transactionId") UUID transactionId);
}
