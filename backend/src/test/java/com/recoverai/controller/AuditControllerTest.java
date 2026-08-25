package com.recoverai.controller;

import com.recoverai.domain.AuditLog;
import com.recoverai.domain.Customer;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
import com.recoverai.repository.AuditLogRepository;
import com.recoverai.repository.CustomerRepository;
import com.recoverai.repository.MerchantRepository;
import com.recoverai.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code @WithMockUser} authenticates every request here as MERCHANT_ADMIN - authentication/authorization itself is covered by {@code AuthenticationIntegrationTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test-admin", roles = "MERCHANT_ADMIN")
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void getByTransaction_returnsChronologicalTimeline() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Audit API Test Merchant")
                .email("audit-api-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Audit API Test Customer")
                .email("audit-api-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_audit_api_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());

        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType("RISK_DETECTED").actor("TEST")
                .reason("first event").timestamp(Instant.now().minusSeconds(60)).build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType("RECOVERY_AI_RECOMMENDATION").actor("AI_AGENT")
                .decision("RETRY_PAYMENT").reason("second event").timestamp(Instant.now()).build());

        mockMvc.perform(get("/api/audit/{transactionId}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventType").value("RISK_DETECTED"))
                .andExpect(jsonPath("$[1].eventType").value("RECOVERY_AI_RECOMMENDATION"))
                .andExpect(jsonPath("$[1].actor").value("AI_AGENT"))
                .andExpect(jsonPath("$[1].decision").value("RETRY_PAYMENT"));
    }

    @Test
    void getByTransaction_noAuditYet_returnsEmptyList() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Audit API Empty Merchant")
                .email("audit-api-empty-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Audit API Empty Customer")
                .email("audit-api-empty-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_audit_empty_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());

        mockMvc.perform(get("/api/audit/{transactionId}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getByTransaction_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(get("/api/audit/{transactionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- P1.4 global audit feed

    private Transaction seedTransactionWithEvents(String eventTypeA, String eventTypeB) {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Global Audit Test Merchant")
                .email("global-audit-" + UUID.randomUUID() + "@example.com").build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant).name("Global Audit Test Customer")
                .email("global-audit-cust-" + UUID.randomUUID() + "@example.com").build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_global_audit_" + UUID.randomUUID())
                .merchant(merchant).customer(customer).amount(new BigDecimal("500.00")).currency("INR")
                .status(TransactionStatus.FAILED).paymentMethod(PaymentMethod.CARD).attemptCount(1).build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType(eventTypeA).actor("POLICY_ENGINE")
                .decision("ALLOW").reason("first").timestamp(Instant.now().minusSeconds(120)).build());
        auditLogRepository.save(AuditLog.builder()
                .transaction(transaction).eventType(eventTypeB).actor("AI_AGENT")
                .reason("second").timestamp(Instant.now()).build());
        return transaction;
    }

    @Test
    void globalAudit_returnsNewestFirstAcrossTransactions() throws Exception {
        Transaction transaction = seedTransactionWithEvents("RECOVERY_POLICY_EVALUATED", "RECOVERY_AI_RECOMMENDATION");

        mockMvc.perform(get("/api/audit").param("transactionId", transaction.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].eventType").value("RECOVERY_AI_RECOMMENDATION"))
                .andExpect(jsonPath("$.content[0].transactionId").value(transaction.getId().toString()))
                .andExpect(jsonPath("$.content[0].externalTransactionId").value(transaction.getExternalTransactionId()))
                .andExpect(jsonPath("$.content[1].eventType").value("RECOVERY_POLICY_EVALUATED"));
    }

    @Test
    void globalAudit_filtersByEventType() throws Exception {
        Transaction transaction = seedTransactionWithEvents("RECOVERY_POLICY_EVALUATED", "RECOVERY_AI_RECOMMENDATION");

        mockMvc.perform(get("/api/audit")
                        .param("transactionId", transaction.getId().toString())
                        .param("eventType", "RECOVERY_POLICY_EVALUATED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].eventType").value("RECOVERY_POLICY_EVALUATED"));
    }

    @Test
    void globalAudit_filtersByActor() throws Exception {
        Transaction transaction = seedTransactionWithEvents("RECOVERY_POLICY_EVALUATED", "RECOVERY_AI_RECOMMENDATION");

        mockMvc.perform(get("/api/audit")
                        .param("transactionId", transaction.getId().toString())
                        .param("actor", "AI_AGENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].actor").value("AI_AGENT"));
    }

    @Test
    void globalAudit_isPaginated() throws Exception {
        seedTransactionWithEvents("RECOVERY_POLICY_EVALUATED", "RECOVERY_AI_RECOMMENDATION");

        mockMvc.perform(get("/api/audit").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }
}
