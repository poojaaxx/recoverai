package com.recoverai.repository;

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
}
