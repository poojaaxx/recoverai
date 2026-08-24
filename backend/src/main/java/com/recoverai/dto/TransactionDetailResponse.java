package com.recoverai.dto;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read-only projection of {@link Transaction} for the detail view. */
public record TransactionDetailResponse(
        UUID id,
        String externalTransactionId,
        UUID merchantId,
        UUID customerId,
        String customerName,
        String customerEmail,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        PaymentMethod paymentMethod,
        String failureCode,
        String failureReason,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransactionDetailResponse from(Transaction transaction) {
        return new TransactionDetailResponse(
                transaction.getId(),
                transaction.getExternalTransactionId(),
                transaction.getMerchant().getId(),
                transaction.getCustomer().getId(),
                transaction.getCustomer().getName(),
                transaction.getCustomer().getEmail(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getPaymentMethod(),
                transaction.getFailureCode(),
                transaction.getFailureReason(),
                transaction.getAttemptCount(),
                transaction.getCreatedAt(),
                transaction.getUpdatedAt()
        );
    }
}
