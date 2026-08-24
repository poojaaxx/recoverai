package com.recoverai.seed;

import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.payment.IdempotencyKeys;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack verification against a real, JVM-managed PostgreSQL instance
 * (io.zonky.test:embedded-postgres — no Docker required). This is the only
 * test in the project that runs with the DEFAULT Spring profile (i.e. the
 * actual application.yml: Flyway enabled, {@code ddl-auto: validate}), so
 * it is the one place that genuinely proves:
 * <p>
 * 1. The V1-V7 Flyway migrations apply cleanly to real PostgreSQL.<br>
 * 2. Hibernate's entity mappings validate against the schema those
 *    migrations actually produced (not just against H2's looser mapping).<br>
 * 3. The {@link DemoDataSeeder} can populate and be read back from real
 *    PostgreSQL, including the {@code jsonb} audit log metadata column.
 * <p>
 * See {@code EntityPersistenceTest} and {@code DemoDataSeederTest} for the
 * faster H2-backed equivalents used for day-to-day development.
 */
@SpringBootTest
class PostgresMigrationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresMigrationIntegrationTest.class);

    private static EmbeddedPostgres embeddedPostgres;

    @BeforeAll
    static void startEmbeddedPostgres() throws IOException {
        embeddedPostgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopEmbeddedPostgres() throws IOException {
        if (embeddedPostgres != null) {
            embeddedPostgres.close();
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> embeddedPostgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    /**
     * Phase 6: proves migration V9's {@code idempotency_key} unique
     * constraint is enforced by real PostgreSQL, not just H2's
     * compatibility mode - see {@code PaymentGatewayIdempotencyTest} for
     * the faster H2-backed equivalent.
     */
    @Test
    void idempotencyKeyUniqueConstraint_isEnforcedOnRealPostgres() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("PG Idempotency Test Merchant")
                .email("pg-idem-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("PG Idempotency Test Customer")
                .email("pg-idem-cust-" + UUID.randomUUID() + "@example.com")
                .build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("pg_idem_txn_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.CARD)
                .attemptCount(1)
                .build());

        String key = IdempotencyKeys.forAttempt(transaction.getId(), RecoveryAction.RETRY_PAYMENT, 1);
        recoveryAttemptRepository.saveAndFlush(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1)
                .idempotencyKey(key)
                .amount(transaction.getAmount())
                .build());

        assertThatThrownBy(() -> recoveryAttemptRepository.saveAndFlush(RecoveryAttempt.builder()
                .transaction(transaction)
                .action(RecoveryAction.RETRY_PAYMENT)
                .status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(2)
                .idempotencyKey(key)
                .amount(transaction.getAmount())
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void flywayMigratesAndSeedDataPersistsOnRealPostgres() {
        // Reaching this point already proves the Spring context started
        // successfully against real PostgreSQL: Flyway applied V1-V7 and
        // Hibernate's ddl-auto=validate confirmed every entity mapping
        // matches the migrated schema exactly.
        SeedReport report = seeder.seed();

        log.info("Seed data quality report (real PostgreSQL, embedded):");
        log.info("Total transactions: {}", report.totalTransactions());
        report.countsByStatus().forEach((status, count) -> log.info("  {}: {}", status, count));
        log.info("High-value (>= 10000): {}", report.highValueCount());
        log.info("Repeated failures (attemptCount >= 2): {}", report.repeatedFailureCount());
        log.info("RevenueRisk rows: {}", report.revenueRiskCount());
        log.info("RecoveryAttempt rows: {}", report.recoveryAttemptCount());
        log.info("AuditLog rows: {}", report.auditLogCount());
        log.info("Demo transactions: {}", report.demoTransactionIds());

        assertThat(report.totalTransactions()).isEqualTo(DemoDataSeeder.TOTAL_TRANSACTIONS);
        assertThat(report.countsByStatus()).containsKeys(
                "SUCCESS", "FAILED", "PENDING", "ABANDONED", "RECOVERED", "ESCALATED", "STOPPED");
        assertThat(report.demoTransactionIds()).hasSize(DemoDataSeeder.DEMO_TRANSACTION_COUNT);
        assertThat(report.revenueRiskCount()).isGreaterThan(0);
        assertThat(report.recoveryAttemptCount()).isGreaterThan(0);
        assertThat(report.auditLogCount()).isGreaterThan(0);
    }
}
