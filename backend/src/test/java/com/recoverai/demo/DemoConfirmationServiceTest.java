package com.recoverai.demo;

import com.recoverai.config.RazorpayProperties;
import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentConfirmationStatus;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.RecoveryAttempt;
import com.recoverai.domain.RecoveryAttemptStatus;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.dto.TestPaymentConfirmationResponse;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.RecoveryAttemptRepository;
import com.recoverai.repository.TransactionRepository;
import com.recoverai.webhook.PaymentConfirmationService;
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
 * P0.4 unit coverage for {@link DemoConfirmationService} - every safety
 * gate exercised directly, without going through HTTP/security (see {@code
 * RecoveryDemoControllerTest}/{@code AuthenticationIntegrationTest} for the
 * HTTP-layer role gating on this endpoint).
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoConfirmationServiceTest {

    private static final String WEBHOOK_SECRET = "test_webhook_secret";

    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecoveryAttemptRepository recoveryAttemptRepository;
    @Autowired
    private PaymentConfirmationService paymentConfirmationService;
    @Autowired
    private RazorpayProperties realRazorpayProperties;

    private Merchant merchant;

    private Merchant merchant() {
        if (merchant == null) {
            merchant = merchantRepository.save(Merchant.builder()
                    .name("Demo Confirmation Test Merchant")
                    .email("democonf-" + UUID.randomUUID() + "@example.com").build());
        }
        return merchant;
    }

    private Transaction transactionWithEligibleMockAttempt() {
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant()).name("Customer " + UUID.randomUUID())
                .email("cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(5).failedPaymentCount(0).build());
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId("democonf_" + UUID.randomUUID())
                .merchant(merchant()).customer(customer).amount(new BigDecimal("999.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(txn.getAmount())
                .provider("mock").providerReference("mock_" + UUID.randomUUID())
                .paymentConfirmationStatus(PaymentConfirmationStatus.NOT_CONFIRMED)
                .executedAt(Instant.now()).build());
        return txn;
    }

    private RazorpayProperties demoModeProperties() {
        RazorpayProperties props = new RazorpayProperties();
        props.setEnabled(false);
        props.setWebhookSecret(WEBHOOK_SECRET);
        return props;
    }

    private DemoConfirmationService service(RazorpayProperties props, boolean demoSeedEnabled) {
        return new DemoConfirmationService(props, demoSeedEnabled, transactionRepository,
                recoveryAttemptRepository, paymentConfirmationService);
    }

    @Test
    void eligibleMockAttempt_confirmsThroughTheRealWebhookPipeline_labeledTestSimulation() {
        Transaction txn = transactionWithEligibleMockAttempt();
        DemoConfirmationService service = service(demoModeProperties(), true);

        TestPaymentConfirmationResponse response = service.confirmTestPayment(txn.getId());

        assertThat(response.label()).contains("TEST/SIMULATION");
        assertThat(response.outcome()).isEqualTo("CONFIRMED");
        assertThat(response.confirmedAmount()).isEqualByComparingTo(txn.getAmount());

        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.RECOVERED);
    }

    @Test
    void demoModeDisabled_refusesEvenWithAnEligibleAttempt() {
        Transaction txn = transactionWithEligibleMockAttempt();
        DemoConfirmationService service = service(demoModeProperties(), false);

        assertThatThrownBy(() -> service.confirmTestPayment(txn.getId()))
                .isInstanceOf(TestConfirmationNotAvailableException.class)
                .hasMessageContaining("demo environment");

        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void realRazorpayEnabled_refusesEvenWithAnEligibleAttempt() {
        Transaction txn = transactionWithEligibleMockAttempt();
        RazorpayProperties props = demoModeProperties();
        props.setEnabled(true);
        DemoConfirmationService service = service(props, true);

        assertThatThrownBy(() -> service.confirmTestPayment(txn.getId()))
                .isInstanceOf(TestConfirmationNotAvailableException.class)
                .hasMessageContaining("real payment provider is active");

        Transaction reloaded = transactionRepository.findById(txn.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void noEligibleAttempt_refusesRatherThanFabricatingAConfirmation() {
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId("democonf_none_" + UUID.randomUUID())
                .merchant(merchant()).customer(customerRepository.save(Customer.builder()
                        .merchant(merchant()).name("No Attempt Customer")
                        .email("noattempt-" + UUID.randomUUID() + "@example.com").build()))
                .amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        DemoConfirmationService service = service(demoModeProperties(), true);

        assertThatThrownBy(() -> service.confirmTestPayment(txn.getId()))
                .isInstanceOf(TestConfirmationNotAvailableException.class)
                .hasMessageContaining("No eligible executed recovery attempt");
    }

    @Test
    void realRazorpayProviderAttempt_isNeverEligible_evenInDemoMode() {
        // A hand-seeded attempt that went through the real gateway (provider="razorpay")
        // must never be usable to fake a confirmation via this path.
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant()).name("Razorpay Attempt Customer")
                .email("rzp-" + UUID.randomUUID() + "@example.com").build());
        Transaction txn = transactionRepository.save(Transaction.builder()
                .externalTransactionId("democonf_rzp_" + UUID.randomUUID())
                .merchant(merchant()).customer(customer).amount(new BigDecimal("750.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        recoveryAttemptRepository.save(RecoveryAttempt.builder()
                .transaction(txn).action(RecoveryAction.RETRY_PAYMENT).status(RecoveryAttemptStatus.SUCCESS)
                .attemptNumber(1).amount(txn.getAmount())
                .provider("razorpay").providerReference("plink_real_" + UUID.randomUUID())
                .paymentConfirmationStatus(PaymentConfirmationStatus.NOT_CONFIRMED)
                .executedAt(Instant.now()).build());
        DemoConfirmationService service = service(demoModeProperties(), true);

        assertThatThrownBy(() -> service.confirmTestPayment(txn.getId()))
                .isInstanceOf(TestConfirmationNotAvailableException.class)
                .hasMessageContaining("No eligible executed recovery attempt");
    }

    @Test
    void alreadyConfirmedAttempt_isNoLongerEligible() {
        Transaction txn = transactionWithEligibleMockAttempt();
        DemoConfirmationService service = service(demoModeProperties(), true);
        service.confirmTestPayment(txn.getId());

        assertThatThrownBy(() -> service.confirmTestPayment(txn.getId()))
                .isInstanceOf(TestConfirmationNotAvailableException.class)
                .hasMessageContaining("No eligible executed recovery attempt");
    }

    @Test
    void blankWebhookSecret_refusesRatherThanConfirmingUnsigned() {
        Transaction txn = transactionWithEligibleMockAttempt();
        RazorpayProperties props = new RazorpayProperties();
        props.setEnabled(false);
        props.setWebhookSecret("");
        DemoConfirmationService service = service(props, true);

        assertThatThrownBy(() -> service.confirmTestPayment(txn.getId()))
                .isInstanceOf(TestConfirmationNotAvailableException.class)
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
    }
}
