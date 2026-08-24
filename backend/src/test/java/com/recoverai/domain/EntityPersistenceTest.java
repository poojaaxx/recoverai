package com.recoverai.domain;

import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.RevenueRiskRepository;
import com.recoverai.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies entity mappings, relationships, and constraints against the
 * project's H2 "test" profile (PostgreSQL-compatibility mode,
 * ddl-auto=create-drop). This does not exercise Flyway or real PostgreSQL
 * behavior — see {@code PostgresMigrationIntegrationTest} for that.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class EntityPersistenceTest {

    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RevenueRiskRepository revenueRiskRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private EntityManager entityManager;

    private Merchant persistMerchant() {
        return merchantRepository.save(Merchant.builder()
                .name("Test Merchant")
                .email("merchant-" + UUID.randomUUID() + "@example.com")
                .build());
    }

    private Customer persistCustomer(Merchant merchant) {
        return customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Test Customer")
                .email("customer-" + UUID.randomUUID() + "@example.com")
                .phone("+919999999999")
                .successfulPaymentCount(5)
                .failedPaymentCount(1)
                .totalHistoricalValue(new BigDecimal("12500.50"))
                .build());
    }

    private Transaction persistTransaction(Merchant merchant, Customer customer) {
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("1999.99"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .failureReason("Issuer bank declined the transaction temporarily")
                .attemptCount(1)
                .build());
    }

    @Test
    void merchantPersistsAndRoundTrips() {
        Merchant saved = persistMerchant();
        entityManager.flush();
        entityManager.clear();

        Merchant reloaded = merchantRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Test Merchant");
        assertThat(reloaded.getEmail()).isEqualTo(saved.getEmail());
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void customerPersistsWithMerchantRelationship() {
        Merchant merchant = persistMerchant();
        Customer customer = persistCustomer(merchant);
        entityManager.flush();
        entityManager.clear();

        Customer reloaded = customerRepository.findById(customer.getId()).orElseThrow();
        assertThat(reloaded.getMerchant().getId()).isEqualTo(merchant.getId());
        assertThat(reloaded.getTotalHistoricalValue()).isEqualByComparingTo("12500.50");

        assertThat(customerRepository.findByMerchantId(merchant.getId()))
                .extracting(Customer::getId)
                .containsExactly(customer.getId());
    }

    @Test
    void transactionPersistsWithMerchantAndCustomerRelationships() {
        Merchant merchant = persistMerchant();
        Customer customer = persistCustomer(merchant);
        Transaction transaction = persistTransaction(merchant, customer);
        entityManager.flush();
        entityManager.clear();

        Transaction reloaded = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloaded.getMerchant().getId()).isEqualTo(merchant.getId());
        assertThat(reloaded.getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(reloaded.getPaymentMethod()).isEqualTo(PaymentMethod.CARD);
        // BigDecimal round-trips exactly through NUMERIC(14,2) — no floating-point drift.
        assertThat(reloaded.getAmount()).isEqualByComparingTo("1999.99");
    }

    @Test
    void duplicateExternalTransactionIdIsRejected() {
        Merchant merchant = persistMerchant();
        Customer customer = persistCustomer(merchant);
        String externalId = "txn_duplicate_" + UUID.randomUUID();

        transactionRepository.save(Transaction.builder()
                .externalTransactionId(externalId)
                .merchant(merchant)
                .customer(customer)
                .amount(BigDecimal.TEN)
                .status(TransactionStatus.SUCCESS)
                .attemptCount(1)
                .build());
        entityManager.flush();

        assertThatThrownBy(() -> {
            transactionRepository.saveAndFlush(Transaction.builder()
                    .externalTransactionId(externalId)
                    .merchant(merchant)
                    .customer(customer)
                    .amount(BigDecimal.ONE)
                    .status(TransactionStatus.SUCCESS)
                    .attemptCount(1)
                    .build());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revenueRiskPersistsAndLinksToTransaction() {
        Merchant merchant = persistMerchant();
        Customer customer = persistCustomer(merchant);
        Transaction transaction = persistTransaction(merchant, customer);

        RevenueRisk risk = revenueRiskRepository.save(RevenueRisk.builder()
                .transaction(transaction)
                .riskScore(new BigDecimal("72.50"))
                .recoveryProbability(new BigDecimal("0.6800"))
                .amountAtRisk(transaction.getAmount())
                .reason("Seed test risk record")
                .build());
        entityManager.flush();
        entityManager.clear();

        RevenueRisk reloaded = revenueRiskRepository.findById(risk.getId()).orElseThrow();
        assertThat(reloaded.getTransaction().getId()).isEqualTo(transaction.getId());
        assertThat(reloaded.getRiskScore()).isEqualByComparingTo("72.50");
        assertThat(reloaded.getRecoveryProbability()).isEqualByComparingTo("0.6800");
    }

    @Test
    void recoveryAttemptPersistsAndLinksToTransaction() {
        Merchant merchant = persistMerchant();
        Customer customer = persistCustomer(merchant);
        Transaction transaction = persistTransaction(merchant, customer);

        RecoveryAttempt attempt = recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.FAILED)
                .attemptNumber(1)
                .reason("Automatic retry after temporary failure")
                .result("Issuer declined again")
                .amount(transaction.getAmount())
                .build());
        entityManager.flush();
        entityManager.clear();

        RecoveryAttempt reloaded = recoveryAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getTransaction().getId()).isEqualTo(transaction.getId());
        assertThat(reloaded.getAction()).isEqualTo(RecoveryAction.RETRY_PAYMENT);
        assertThat(reloaded.getStatus()).isEqualTo(RecoveryAttemptStatus.FAILED);

        assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(transaction.getId()))
                .hasSize(1);
    }

    @Test
    void auditLogPersistsWithJsonMetadataAndLinksToTransaction() {
        Merchant merchant = persistMerchant();
        Customer customer = persistCustomer(merchant);
        Transaction transaction = persistTransaction(merchant, customer);

        AuditLog log = auditLogRepository.save(AuditLog.builder()
                .transaction(transaction)
                .eventType("RISK_DETECTED")
                .actor("SEED_SCRIPT")
                .decision("N/A")
                .reason("Synthetic seed record for persistence testing")
                .metadata(Map.of("riskScore", 72.5, "factors", Map.of("attemptCount", 1)))
                .build());
        entityManager.flush();
        entityManager.clear();

        AuditLog reloaded = auditLogRepository.findById(log.getId()).orElseThrow();
        assertThat(reloaded.getTransaction().getId()).isEqualTo(transaction.getId());
        assertThat(reloaded.getEventType()).isEqualTo("RISK_DETECTED");
        assertThat(reloaded.getMetadata()).containsEntry("riskScore", 72.5);
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> {
            merchantRepository.saveAndFlush(Merchant.builder().email("no-name@example.com").build());
        }).isInstanceOf(DataAccessException.class);
    }
}
