package com.recoverai.payment;

import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves migration V9's {@code uq_recovery_attempts_idempotency_key}
 * database constraint actually prevents the same recovery attempt from
 * producing two persisted executions - a schema-level guarantee, not
 * application-level "check then insert" logic that could race.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentGatewayIdempotencyTest {

    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Idempotency Test Merchant")
                .email("idem-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Idempotency Test Customer")
                .email("idem-cust-" + UUID.randomUUID() + "@example.com")
                .build());
        transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("idem_txn_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("2499.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.CARD)
                .attemptCount(1)
                .build());
    }

    private RecoveryAttempt attempt(int attemptNumber, String idempotencyKey) {
        return RecoveryAttempt.builder()
                .transaction(transaction)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(attemptNumber)
                .idempotencyKey(idempotencyKey)
                .amount(transaction.getAmount())
                .build();
    }

    @Test
    void duplicateIdempotencyKey_isRejectedByDatabaseConstraint() {
        String key = IdempotencyKeys.forAttempt(transaction.getId(), RecoveryAction.RETRY_PAYMENT, 1);
        recoveryAttemptRepository.saveAndFlush(attempt(1, key));

        assertThatThrownBy(() -> recoveryAttemptRepository.saveAndFlush(attempt(2, key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentIdempotencyKeys_areBothAllowed() {
        String keyOne = IdempotencyKeys.forAttempt(transaction.getId(), RecoveryAction.RETRY_PAYMENT, 1);
        String keyTwo = IdempotencyKeys.forAttempt(transaction.getId(), RecoveryAction.RETRY_PAYMENT, 2);

        recoveryAttemptRepository.saveAndFlush(attempt(1, keyOne));
        recoveryAttemptRepository.saveAndFlush(attempt(2, keyTwo));

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transaction.getId())).hasSize(2);
    }

    @Test
    void nullIdempotencyKey_doesNotCollideWithOtherNulls() {
        // Rows predating Phase 6 (seed data, older attempts) leave this column null;
        // PostgreSQL/H2 both treat multiple NULLs as distinct under a UNIQUE constraint.
        recoveryAttemptRepository.saveAndFlush(attempt(1, null));
        recoveryAttemptRepository.saveAndFlush(attempt(2, null));

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transaction.getId())).hasSize(2);
    }

    @Test
    void idempotencyKey_isDeterministic_sameInputsProduceSameKey() {
        UUID transactionId = UUID.randomUUID();
        String first = IdempotencyKeys.forAttempt(transactionId, RecoveryAction.RETRY_PAYMENT, 1);
        String second = IdempotencyKeys.forAttempt(transactionId, RecoveryAction.RETRY_PAYMENT, 1);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void idempotencyKey_differsByAction_evenForSameTransactionAndAttemptNumber() {
        UUID transactionId = UUID.randomUUID();
        String retryKey = IdempotencyKeys.forAttempt(transactionId, RecoveryAction.RETRY_PAYMENT, 1);
        String linkKey = IdempotencyKeys.forAttempt(transactionId, RecoveryAction.CREATE_PAYMENT_LINK, 1);

        assertThat(retryKey).isNotEqualTo(linkKey);
    }
}
