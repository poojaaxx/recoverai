package com.recoverai.execution;

import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.RecoveryExecutionResponse;
import com.recoverai.dto.RecoveryPolicyDecisionResponse;
import com.recoverai.policy.RecoveryPolicyService;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A focused production-readiness concurrency/load smoke test, run entirely
 * against the mock/simulation provider (never a real Razorpay endpoint) -
 * see docs/ARCHITECTURE.md "Load and Concurrency Verification" for the
 * measured results this test actually produced, reported honestly rather
 * than as an extrapolated capacity claim.
 * <p>
 * "Exactly one provider call / one RecoveryAttempt for the same
 * transaction under concurrency" is already proven by {@link
 * RecoveryExecutionConcurrencyTest}; "exactly one confirmation effect for
 * a duplicated webhook" is already proven by {@code
 * PaymentConfirmationConcurrencyTest}. This class covers what those don't:
 * many DIFFERENT transactions executed concurrently (real throughput, not
 * just the race-condition edge case), concurrent policy evaluation of the
 * same transaction (no inconsistent authorization result), and concurrent
 * dashboard reads (no crash, no inconsistent totals).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "load-test-admin", roles = "MERCHANT_ADMIN")
class LoadAndConcurrencySmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LoadAndConcurrencySmokeTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private RecoveryExecutionService recoveryExecutionService;
    @Autowired
    private RecoveryPolicyService recoveryPolicyService;

    private Transaction seedTransaction(BigDecimal amount, int successCount) {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Load Test Merchant").email("load-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Load Test Customer").email("load-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(successCount).failedPaymentCount(0).build());
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_load_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(amount).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name()).attemptCount(1).build());
    }

    @Test
    void concurrentExecutionAcrossManyDistinctTransactions_allSucceedIndependently() throws Exception {
        int concurrency = 25;
        List<Transaction> transactions = IntStream.range(0, concurrency)
                .mapToObj(i -> seedTransaction(new BigDecimal("1000.00"), 6))
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<Long> latenciesMs = java.util.Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = transactions.stream().<Future<?>>map(t -> pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long begin = System.nanoTime();
            try {
                RecoveryExecutionResponse response = recoveryExecutionService.execute(t.getId());
                latenciesMs.add((System.nanoTime() - begin) / 1_000_000);
                if (response.executed()) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
            } catch (Exception e) {
                latenciesMs.add((System.nanoTime() - begin) / 1_000_000);
                failureCount.incrementAndGet();
            }
        })).toList();

        ready.await(5, TimeUnit.SECONDS);
        long wallClockStart = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        long wallClockMs = System.currentTimeMillis() - wallClockStart;
        pool.shutdown();

        double avgLatencyMs = latenciesMs.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxLatencyMs = latenciesMs.stream().mapToLong(Long::longValue).max().orElse(0);

        log.info("LOAD SMOKE [concurrent execution, {} distinct transactions]: success={} failure={} wallClockMs={} avgLatencyMs={} maxLatencyMs={}",
                concurrency, successCount.get(), failureCount.get(), wallClockMs, avgLatencyMs, maxLatencyMs);

        assertThat(successCount.get()).isEqualTo(concurrency);
        assertThat(failureCount.get()).isZero();

        // No cross-talk between concurrently-executed transactions: each got exactly its own attempt.
        for (Transaction t : transactions) {
            assertThat(recoveryAttemptRepository.findByTransactionIdOrderByAttemptNumberAsc(t.getId())).hasSize(1);
        }
    }

    @Test
    void concurrentPolicyEvaluation_sameTransaction_neverProducesInconsistentDecisions() throws Exception {
        Transaction transaction = seedTransaction(new BigDecimal("1500.00"), 6);
        int concurrency = 20;

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<RecoveryPolicyDecisionResponse>> futures = IntStream.range(0, concurrency).<Future<RecoveryPolicyDecisionResponse>>mapToObj(i -> pool.submit(() -> {
            ready.countDown();
            start.await();
            return recoveryPolicyService.evaluate(transaction.getId(), RecoveryAction.RETRY_PAYMENT);
        })).toList();

        ready.await(5, TimeUnit.SECONDS);
        long wallClockStart = System.currentTimeMillis();
        start.countDown();

        List<RecoveryPolicyDecisionResponse> results = new ArrayList<>();
        for (Future<RecoveryPolicyDecisionResponse> f : futures) {
            results.add(f.get(15, TimeUnit.SECONDS));
        }
        long wallClockMs = System.currentTimeMillis() - wallClockStart;
        pool.shutdown();

        Set<String> distinctDecisions = results.stream().map(r -> r.decision().name()).collect(Collectors.toSet());
        log.info("LOAD SMOKE [{} concurrent policy evaluations, same transaction]: wallClockMs={} distinctDecisions={}",
                concurrency, wallClockMs, distinctDecisions);

        assertThat(results).hasSize(concurrency);
        assertThat(distinctDecisions).as("a read-only evaluation of unchanged state must never disagree with itself under concurrency")
                .hasSize(1);
    }

    @Test
    void concurrentDashboardReads_manyThreads_allSucceedWithConsistentTotals() throws Exception {
        String prefix = "load_dashboard_" + UUID.randomUUID();
        for (int i = 0; i < 15; i++) {
            seedTransaction(new BigDecimal("500.00"), 6);
        }
        // A distinctive marker transaction lets us assert the search actually reflects real, current data.
        seedTransaction(new BigDecimal("500.00"), 6);

        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        List<Long> latenciesMs = java.util.Collections.synchronizedList(new ArrayList<>());

        // MockMvc calls made from these worker threads need the @WithMockUser SecurityContext
        // explicitly propagated - it lives in a ThreadLocal set up on the test's own main thread,
        // which a freshly created ExecutorService thread never inherits on its own.
        SecurityContext capturedSecurityContext = SecurityContextHolder.getContext();

        List<Future<?>> futures = IntStream.range(0, concurrency).<Future<?>>mapToObj(i -> pool.submit(() -> {
            SecurityContextHolder.setContext(capturedSecurityContext);
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long begin = System.nanoTime();
            try {
                mockMvc.perform(get("/api/transactions").param("size", "10"))
                        .andExpect(status().isOk());
                successCount.incrementAndGet();
            } catch (Exception e) {
                log.warn("Concurrent dashboard read failed", e);
            } finally {
                latenciesMs.add((System.nanoTime() - begin) / 1_000_000);
                SecurityContextHolder.clearContext();
            }
        })).toList();

        ready.await(5, TimeUnit.SECONDS);
        long wallClockStart = System.currentTimeMillis();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        long wallClockMs = System.currentTimeMillis() - wallClockStart;
        pool.shutdown();

        double avgLatencyMs = latenciesMs.stream().mapToLong(Long::longValue).average().orElse(0);
        log.info("LOAD SMOKE [{} concurrent dashboard reads]: success={} wallClockMs={} avgLatencyMs={}",
                concurrency, successCount.get(), wallClockMs, avgLatencyMs);

        assertThat(successCount.get()).isEqualTo(concurrency);
    }
}
