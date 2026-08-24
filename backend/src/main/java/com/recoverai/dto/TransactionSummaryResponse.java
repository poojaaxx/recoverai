package com.recoverai.dto;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of {@link Transaction} for list views. Deliberately
 * does not expose the JPA entity (or its lazy {@code merchant}/{@code
 * customer} associations) directly over HTTP.
 */
public record TransactionSummaryResponse(
        UUID id,
        String externalTransactionId,
        UUID customerId,
        String customerName,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        PaymentMethod paymentMethod,
        int attemptCount,
        Instant createdAt
) {
    public static TransactionSummaryResponse from(Transaction transaction) {
        return new TransactionSummaryResponse(
                transaction.getId(),
                transaction.getExternalTransactionId(),
                transaction.getCustomer().getId(),
                transaction.getCustomer().getName(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getPaymentMethod(),
                transaction.getAttemptCount(),
                transaction.getCreatedAt()
        );
    }
}
