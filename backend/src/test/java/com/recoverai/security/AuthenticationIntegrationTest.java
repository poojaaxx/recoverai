package com.recoverai.security;

import com.recoverai.domain.AppUser;
import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.domain.UserRole;
import com.recoverai.repository.AppUserRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage of the production-readiness authentication/
 * authorization layer over real HTTP, with Spring Security genuinely
 * active (unlike every other controller test in this suite, which uses
 * {@code @WithMockUser} to authenticate as MERCHANT_ADMIN and focus on its
 * own endpoint's behavior). This is the one place that proves the security
 * layer itself - not just individual endpoints - actually works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private static final String ADMIN_USERNAME = "auth-it-admin";
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";
    private static final String OPERATOR_USERNAME = "auth-it-operator";
    private static final String OPERATOR_PASSWORD = "another-strong-password";

    @BeforeEach
    void seedUsers() {
        appUserRepository.findByUsername(ADMIN_USERNAME).orElseGet(() -> appUserRepository.save(AppUser.builder()
                .username(ADMIN_USERNAME)
                .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(UserRole.MERCHANT_ADMIN)
                .build()));
        appUserRepository.findByUsername(OPERATOR_USERNAME).orElseGet(() -> appUserRepository.save(AppUser.builder()
                .username(OPERATOR_USERNAME)
                .passwordHash(passwordEncoder.encode(OPERATOR_PASSWORD))
                .role(UserRole.OPERATOR)
                .build()));
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void health_isPublic_noAuthRequired() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void login_validCredentials_returnsTokenAndRole() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(ADMIN_USERNAME, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("MERCHANT_ADMIN"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"wrong-password\"}".formatted(ADMIN_USERNAME)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void login_unknownUsername_returns401_sameShapeAsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no-such-user\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unauthenticatedRequest_toProtectedEndpoint_isRejected() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbageToken_isRejectedAsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedRequest_withValidToken_isAllowed() throws Exception {
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void auditEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/audit/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/recovery/metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metricsEndpoint_allowedForAuthenticatedOperator() throws Exception {
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(get("/api/recovery/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void executionEndpoint_requiresAuthentication() throws Exception {
        Transaction transaction = seedTransaction();

        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void executionEndpoint_operatorRole_isForbidden() throws Exception {
        Transaction transaction = seedTransaction();
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void executionEndpoint_merchantAdminRole_isAllowed() throws Exception {
        Transaction transaction = seedTransaction();
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void confirmTestPaymentEndpoint_requiresAuthentication() throws Exception {
        Transaction transaction = seedTransaction();

        mockMvc.perform(post("/api/demo/recovery/confirm-test-payment/{id}", transaction.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void confirmTestPaymentEndpoint_operatorRole_isForbidden() throws Exception {
        // The role gate runs before the service's own demo-mode/eligibility checks, so this is
        // rejected as 403 regardless of whether demo mode is enabled in this test profile.
        Transaction transaction = seedTransaction();
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/demo/recovery/confirm-test-payment/{id}", transaction.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void noSecurityBypassThroughUnmappedPath_stillRequiresAuthentication() throws Exception {
        // /api/payments/execute deliberately does not exist (see RecoveryExecutionControllerTest),
        // but the request must be rejected for lack of authentication before routing even matters.
        mockMvc.perform(post("/api/payments/execute"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookEndpoint_neverRequiresUserAuthentication_signatureIsTheOnlyGate() throws Exception {
        // No Authorization header at all; rejected for a missing HMAC signature (400), never 401/403 -
        // proving the webhook path is governed by RazorpayWebhookSignature, not this security layer.
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private Transaction seedTransaction() {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Auth IT Merchant")
                .email("auth-it-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Auth IT Customer")
                .email("auth-it-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(6)
                .failedPaymentCount(0)
                .build());
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_auth_it_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("1999.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .attemptCount(1)
                .build());
    }
}
