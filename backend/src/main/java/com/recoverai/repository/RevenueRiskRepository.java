package com.recoverai.repository;

import com.recoverai.domain.RevenueRisk;
import com.recoverai.domain.RiskLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface RevenueRiskRepository extends JpaRepository<RevenueRisk, UUID> {

    /** transaction_id is unique (V8) - one current risk record per transaction, updated in place on re-analysis. */
    Optional<RevenueRisk> findByTransactionId(UUID transactionId);

    Page<RevenueRisk> findByRiskLevel(RiskLevel riskLevel, Pageable pageable);

    @Query("SELECT COUNT(r) FROM RevenueRisk r WHERE r.amountAtRisk > 0")
    long countAtRisk();

    @Query("SELECT COALESCE(SUM(r.amountAtRisk), 0) FROM RevenueRisk r WHERE r.amountAtRisk > 0")
    BigDecimal sumAmountAtRisk();

    @Query("SELECT COALESCE(SUM(r.amountAtRisk), 0) FROM RevenueRisk r WHERE r.riskLevel = :riskLevel AND r.amountAtRisk > 0")
    BigDecimal sumAmountAtRiskByRiskLevel(@Param("riskLevel") RiskLevel riskLevel);

    @Query("SELECT COALESCE(AVG(r.recoveryProbability), 0) FROM RevenueRisk r WHERE r.amountAtRisk > 0")
    BigDecimal averageRecoveryProbability();

    @Query("SELECT COALESCE(SUM(r.amountAtRisk * r.recoveryProbability), 0) FROM RevenueRisk r WHERE r.amountAtRisk > 0")
    BigDecimal sumPotentialRecoveryValue();
}
