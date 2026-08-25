package com.recoverai.dto;

import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of {@link Transaction} for the detail view.
 * <p>
 * Phase 10 data-minimization: {@code customerEmail} is partially masked
 * before ever leaving the server - this API has no authentication, and
 * nothing in this project's frontend currently reads the raw address (a
 * merchant-facing dashboard that needs the full address for contacting a
 * customer is a legitimate, larger, authenticated-endpoint concern for a
 * later phase, not this read-only demo API). The field is kept, not
 * removed, since a masked address still lets a merchant recognize a
 * repeat customer at a glance.
 */
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
                maskEmail(transaction.getCustomer().getEmail()),
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

    /** {@code "j***n@example.com"} - keeps the first/last local-part characters and the full domain, masks the rest. */
    private static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }
}
