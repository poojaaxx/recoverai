package com.recoverai.demo;

import com.recoverai.config.RazorpayProperties;
import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.seed.DemoDataSeeder;
import com.recoverai.seed.SeedReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link DemoResetService} - both its safety gates (mirroring
 * {@code DemoConfirmationServiceTest}'s pattern) and the actual claim this
 * capability exists to prove: that a "drifted" demo dataset (transactions
 * mutated away from their seeded state, extra rows added) is restored to
 * exactly the original deterministic state after {@code reset()}.
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoResetServiceTest {

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private RazorpayProperties disabledRazorpay() {
        RazorpayProperties props = new RazorpayProperties();
        props.setEnabled(false);
        return props;
    }

    private DemoResetService service(boolean demoSeedEnabled, boolean razorpayEnabled) {
        RazorpayProperties props = disabledRazorpay();
        props.setEnabled(razorpayEnabled);
        return new DemoResetService(seeder, props, demoSeedEnabled);
    }

    @Test
    void demoModeDisabled_refusesAndChangesNothing() {
        seeder.seed();
        long before = transactionRepository.count();
        DemoResetService service = service(false, false);

        assertThatThrownBy(service::reset)
                .isInstanceOf(DemoResetNotAvailableException.class)
                .hasMessageContaining("demo environment");

        assertThat(transactionRepository.count()).isEqualTo(before);
    }

    @Test
    void realRazorpayEnabled_refusesAndChangesNothing() {
        seeder.seed();
        long before = transactionRepository.count();
        DemoResetService service = service(true, true);

        assertThatThrownBy(service::reset)
                .isInstanceOf(DemoResetNotAvailableException.class)
                .hasMessageContaining("real payment provider is active");

        assertThat(transactionRepository.count()).isEqualTo(before);
    }

    @Test
    void reset_restoresTheOriginalDeterministicDemoState_afterDrift() {
        DemoResetService service = service(true, false);
        service.reset();

        // Sanity: the 5 named demo transactions exist with their original seeded
        // status/amount before we drift anything.
        Transaction easyRecovery = transactionRepository.findByExternalTransactionId("demo-easy-recovery")
                .orElseThrow();
        assertThat(easyRecovery.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(easyRecovery.getAmount()).isEqualByComparingTo(new BigDecimal("2499.00"));

        // Drift the dataset: mutate a demo transaction's status/amount directly (simulating
        // what a live execution/confirmation would do), and add extra rows a real demo
        // session could accumulate (an unrelated transaction plus its own audit trail).
        easyRecovery.setStatus(TransactionStatus.RECOVERED);
        easyRecovery.setAmount(new BigDecimal("999999.00"));
        transactionRepository.save(easyRecovery);

        Merchant merchant = merchantRepository.findAll().get(0);
        Customer strayCustomer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Stray Test Customer")
                .email("stray-" + UUID.randomUUID() + "@example.com").build());
        transactionRepository.save(Transaction.builder()
                .externalTransactionId("stray_" + UUID.randomUUID())
                .merchant(merchant).customer(strayCustomer)
                .amount(new BigDecimal("1234.00")).currency("INR")
                .status(TransactionStatus.FAILED).attemptCount(1).build());

        auditLogRepository.save(AuditLog.builder()
                .transaction(easyRecovery)
                .eventType("STRAY_TEST_EVENT")
                .actor("TEST")
                .reason("Simulated drift for the reset test")
                .timestamp(Instant.now())
                .build());

        assertThat(transactionRepository.findByExternalTransactionId("demo-easy-recovery").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.RECOVERED);

        // Act: reset.
        SeedReport report = service.reset();

        // The report itself proves a fresh, complete deterministic reseed happened.
        assertThat(report.totalTransactions()).isEqualTo(DemoDataSeeder.TOTAL_TRANSACTIONS);
        assertThat(report.demoTransactionIds()).hasSize(DemoDataSeeder.DEMO_TRANSACTION_COUNT);
        assertThat(report.demoTransactionIds().values()).containsExactlyInAnyOrder(
                "demo-easy-recovery", "demo-retry-escalation", "demo-high-value",
                "demo-repeated-failure", "demo-successful-recovery");

        // The drifted transaction is back to its original seeded state - not the drifted one.
        Transaction restored = transactionRepository.findByExternalTransactionId("demo-easy-recovery").orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(restored.getAmount()).isEqualByComparingTo(new BigDecimal("2499.00"));
        // A fresh row (new id) proves this came from a real wipe-and-regenerate, not an in-place edit.
        assertThat(restored.getId()).isNotEqualTo(easyRecovery.getId());

        // The stray drift-only audit row (and everything else from the pre-reset state,
        // including the stray transaction's would-be audit trail) is gone - nothing survives
        // a reset except what the deterministic seeder itself produces.
        assertThat(auditLogRepository.findAll())
                .noneMatch(log -> "STRAY_TEST_EVENT".equals(log.getEventType()));
        assertThat(transactionRepository.findAll())
                .noneMatch(t -> t.getExternalTransactionId().startsWith("stray_"));

        // Every other named demo transaction is present with its original seeded status too.
        assertThat(transactionRepository.findByExternalTransactionId("demo-high-value").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.FAILED);
        assertThat(transactionRepository.findByExternalTransactionId("demo-repeated-failure").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.STOPPED);
        assertThat(transactionRepository.findByExternalTransactionId("demo-successful-recovery").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.RECOVERED);
        assertThat(transactionRepository.findByExternalTransactionId("demo-retry-escalation").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.ESCALATED);
    }

    @Test
    void reset_isSafeToCallRepeatedly_alwaysLandingOnTheSameDeterministicShape() {
        DemoResetService service = service(true, false);

        SeedReport first = service.reset();
        SeedReport second = service.reset();

        assertThat(second.totalTransactions()).isEqualTo(first.totalTransactions());
        assertThat(second.recoveryAttemptCount()).isEqualTo(first.recoveryAttemptCount());
        assertThat(second.auditLogCount()).isEqualTo(first.auditLogCount());
        assertThat(second.demoTransactionIds()).isEqualTo(first.demoTransactionIds());
    }
}
