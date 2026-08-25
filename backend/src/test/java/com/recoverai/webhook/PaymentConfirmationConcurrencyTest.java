package com.recoverai.webhook;

import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves "two identical webhook deliveries -&gt; exactly one confirmation"
 * holds under genuine thread-level concurrency, not just sequential replay
 * - see {@code PaymentConfirmationService}'s javadoc for the database
 * -constraint mechanism (the {@code webhook_events.provider_event_id}
 * unique index, migration V11) this relies on. Mirrors {@code
 * RecoveryExecutionConcurrencyTest}'s pattern for the equivalent Phase 7
 * guarantee.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentConfirmationConcurrencyTest {

    private static final String SECRET = "test_webhook_secret";

    @Autowired
    private PaymentConfirmationService service;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;

    @Test
    void twoConcurrentIdenticalWebhookDeliveries_resultInExactlyOneConfirmation() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Webhook Concurrency Merchant").email("webhook-conc-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Webhook Concurrency Customer")
                .email("webhook-conc-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("webhook_conc_txn_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("3000.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name()).attemptCount(1).build());
        RecoveryAttempt attempt = recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(transaction).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(new BigDecimal("3000.00")).provider("razorpay")
                .providerReference("plink_concurrency").executedAt(Instant.now())
                .idempotencyKey(transaction.getId() + ":RETRY_PAYMENT:1").build());

        String payload = """
                {"event":"payment_link.paid","payload":{\
                "payment_link":{"entity":{"id":"plink_concurrency"}},\
                "payment":{"entity":{"id":"pay_concurrency","amount":300000,"currency":"INR"}}}}""";
        String signature = RazorpayWebhookSignature.sign(payload, SECRET);

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<WebhookProcessingResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return service.processRazorpayWebhook(payload, signature, "evt_concurrency_shared");
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<WebhookProcessingResult> results = new ArrayList<>();
        for (var future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long confirmedCount = results.stream().filter(r -> r.outcome() == WebhookOutcome.CONFIRMED).count();
        long alreadyProcessedCount = results.stream().filter(r -> r.outcome() == WebhookOutcome.ALREADY_PROCESSED).count();
        assertThat(confirmedCount).isEqualTo(1);
        assertThat(alreadyProcessedCount).isEqualTo(1);

        RecoveryAttempt reloaded = recoveryAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertThat(reloaded.getAmountRecovered()).isEqualByComparingTo("3000.00");

        Transaction reloadedTxn = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(reloadedTxn.getStatus()).isEqualTo(TransactionStatus.RECOVERED);
    }
}
