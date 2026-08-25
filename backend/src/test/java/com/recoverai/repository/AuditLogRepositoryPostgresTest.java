package com.recoverai.repository;

import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic: reproduces the P1.4 global-audit-search 500 seen against the
 * real (Neon) PostgreSQL deployment, using a real embedded PostgreSQL
 * instance instead of H2 - see {@code PostgresMigrationIntegrationTest} for
 * why H2 alone can hide real-Postgres-only bugs.
 */
@SpringBootTest
class AuditLogRepositoryPostgresTest {

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
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void globalSearch_allNullFilters_onRealPostgres() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("PG Audit Test Merchant").email("pg-audit-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("PG Audit Test Customer")
                .email("pg-audit-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("pg_audit_txn_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType("RISK_DETECTED").actor("TEST")
                .reason("real postgres test").timestamp(Instant.now()).build());

        var page = auditLogRepository.search(null, null, null, Instant.EPOCH,
                Instant.parse("9999-12-31T23:59:59Z"), PageRequest.of(0, 25));

        assertThat(page.getContent()).isNotEmpty();
    }

    @Test
    void globalSearch_withTransactionIdFilter_onRealPostgres() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("PG Audit Test Merchant 2").email("pg-audit2-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("PG Audit Test Customer 2")
                .email("pg-audit2-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("pg_audit_txn2_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType("RISK_DETECTED").actor("TEST")
                .reason("real postgres test 2").timestamp(Instant.now()).build());

        var page = auditLogRepository.search(null, null, transaction.getId(), Instant.EPOCH,
                Instant.parse("9999-12-31T23:59:59Z"), PageRequest.of(0, 25));

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void globalSearch_narrowDateRangeExcludesOldEvent_onRealPostgres() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("PG Audit Test Merchant 3").email("pg-audit3-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("PG Audit Test Customer 3")
                .email("pg-audit3-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("pg_audit_txn3_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType("RISK_DETECTED").actor("TEST")
                .reason("old event").timestamp(Instant.now().minusSeconds(3600 * 24 * 30)).build());

        var page = auditLogRepository.search(null, null, null, Instant.now().minusSeconds(60), Instant.now(),
                PageRequest.of(0, 25));

        assertThat(page.getContent()).noneMatch(a -> a.getTransaction().getId().equals(transaction.getId()));
    }
}
