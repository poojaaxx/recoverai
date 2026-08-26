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
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired
    private AuthProperties authProperties;

    private static final String ADMIN_USERNAME = "auth-it-admin";
    private static final String ADMIN_PASSWORD = "correct-horse-battery-staple";
    private static final String OPERATOR_USERNAME = "auth-it-operator";
    private static final String OPERATOR_PASSWORD = "another-strong-password";
    private static final String REVOKE_USERNAME = "auth-it-revoke-subject";
    private static final String REVOKE_PASSWORD = "yet-another-strong-password";

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
        // A dedicated identity for refresh/revocation tests, kept separate from the two
        // fixtures above so incrementing its tokenVersion (via /logout) can never interfere
        // with any other test's assumption that ADMIN/OPERATOR credentials always work.
        appUserRepository.findByUsername(REVOKE_USERNAME).orElseGet(() -> appUserRepository.save(AppUser.builder()
                .username(REVOKE_USERNAME)
                .passwordHash(passwordEncoder.encode(REVOKE_PASSWORD))
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

    /**
     * A validly-signed (real secret, read from {@link AuthProperties} rather than hardcoded) but
     * already-expired token - built directly since {@code JwtService.issueToken()} has no way to
     * produce one by design. Used to prove expired tokens are rejected end-to-end over real HTTP,
     * not just at the {@code JwtService.parseClaims()} unit level ({@code JwtServiceTest} covers that).
     */
    private String expiredTokenFor(String username, UserRole role) {
        SecretKey key = Keys.hmacShaKeyFor(authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        return Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .claim("tv", 0)
                .issuedAt(Date.from(past.minus(1, ChronoUnit.HOURS)))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
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
    void approveEndpoint_requiresAuthentication() throws Exception {
        Transaction transaction = seedTransaction();

        mockMvc.perform(post("/api/recovery/{id}/approve", transaction.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approveEndpoint_operatorRole_isForbidden() throws Exception {
        Transaction transaction = seedTransaction();
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/recovery/{id}/approve", transaction.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectEndpoint_operatorRole_isForbidden() throws Exception {
        Transaction transaction = seedTransaction();
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/recovery/{id}/reject", transaction.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void batchExecuteEndpoint_requiresAuthentication() throws Exception {
        Transaction transaction = seedTransaction();

        mockMvc.perform(post("/api/recovery/batch/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionIds\":[\"%s\"]}".formatted(transaction.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void batchExecuteEndpoint_operatorRole_isForbidden() throws Exception {
        Transaction transaction = seedTransaction();
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/recovery/batch/execute")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionIds\":[\"%s\"]}".formatted(transaction.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void batchExecuteEndpoint_merchantAdminRole_isAllowed() throws Exception {
        Transaction transaction = seedTransaction();
        String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(post("/api/recovery/batch/execute")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionIds\":[\"%s\"]}".formatted(transaction.getId())))
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
    void resetEndpoint_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/demo/recovery/reset"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resetEndpoint_operatorRole_isForbidden() throws Exception {
        // The role gate runs before the service's own demo-mode check, so this is rejected
        // as 403 regardless of whether demo mode is enabled in this test profile (it isn't -
        // see DemoResetServiceTest for the 409-when-disabled behavior with the role gate absent).
        String token = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/demo/recovery/reset")
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

    // ---------------------------------------------------------------- expiry

    @Test
    void expiredToken_onProtectedEndpoint_isRejectedAsUnauthenticated() throws Exception {
        String expired = expiredTokenFor(OPERATOR_USERNAME, UserRole.OPERATOR);

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refresh_withValidToken_issuesANewWorkingToken() throws Exception {
        String original = login(REVOKE_USERNAME, REVOKE_PASSWORD);

        String body = mockMvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn().getResponse().getContentAsString();
        String refreshed = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // Not asserting refreshed != original here: a JWT's iat/exp are second-granularity, so a
        // login immediately followed by a refresh can legitimately produce byte-for-byte identical
        // tokens (same subject/role/tokenVersion, same second) - that's not a bug, just a property
        // of JWT timestamps. What actually matters is that the refreshed token is real and works:
        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + refreshed))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_withoutAuthentication_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_withExpiredToken_isRejected() throws Exception {
        String expired = expiredTokenFor(REVOKE_USERNAME, UserRole.OPERATOR);

        mockMvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_withGarbageToken_isRejected() throws Exception {
        mockMvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- revocation (logout)

    @Test
    void logout_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokesToken_theSameTokenNoLongerWorksAfterward() throws Exception {
        String token = login(REVOKE_USERNAME, REVOKE_PASSWORD);
        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_thenLoginAgain_getsAFreshWorkingToken() throws Exception {
        String token = login(REVOKE_USERNAME, REVOKE_PASSWORD);
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String freshToken = login(REVOKE_USERNAME, REVOKE_PASSWORD);

        assertThat(freshToken).isNotEqualTo(token);
        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_afterLogout_theOldTokenCannotRefreshEither() throws Exception {
        String token = login(REVOKE_USERNAME, REVOKE_PASSWORD);
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_doesNotAffectOtherAccounts() throws Exception {
        // A logout revokes only the caller's own tokens - proves the tokenVersion increment is
        // scoped to the single AppUser row, never a global counter that would revoke everyone.
        String revokeSubjectToken = login(REVOKE_USERNAME, REVOKE_PASSWORD);
        String operatorToken = login(OPERATOR_USERNAME, OPERATOR_PASSWORD);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + revokeSubjectToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transactions").header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk());
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
