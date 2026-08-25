package com.recoverai.controller;

import com.recoverai.domain.Customer;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.Merchant;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.Transaction;
import com.recoverai.domain.TransactionStatus;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code @WithMockUser} authenticates every request here as MERCHANT_ADMIN - authentication/authorization itself is covered by {@code AuthenticationIntegrationTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test-admin", roles = "MERCHANT_ADMIN")
class RecoveryAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void evaluateEndpoint_returnsFullPipelineResultOverHttp() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("2499.00"));

        mockMvc.perform(post("/api/recovery-agent/evaluate/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getId().toString()))
                .andExpect(jsonPath("$.aiRecommendation.action").exists())
                .andExpect(jsonPath("$.aiRecommendation.confidence").exists())
                .andExpect(jsonPath("$.aiRecommendation.rationale").isNotEmpty())
                .andExpect(jsonPath("$.aiRecommendation.provider").value("mock"))
                .andExpect(jsonPath("$.policyDecision.decision").exists())
                .andExpect(jsonPath("$.policyDecision.policyChecks").isArray())
                .andExpect(jsonPath("$.finalAction").exists())
                .andExpect(jsonPath("$.requiresHumanApproval").exists())
                .andExpect(jsonPath("$.auditEventId").exists());
    }

    @Test
    void evaluateEndpoint_highValueTransaction_finalActionIsEscalate() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("47500.00"));

        mockMvc.perform(post("/api/recovery-agent/evaluate/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyDecision.decision").value("ESCALATE"))
                .andExpect(jsonPath("$.finalAction").value("ESCALATE"))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true));
    }

    @Test
    void evaluateEndpoint_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(post("/api/recovery-agent/evaluate/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluateAllEndpoint_returnsAggregatedStatistics() throws Exception {
        seedTransaction(TransactionStatus.FAILED, new BigDecimal("2499.00"));

        mockMvc.perform(post("/api/recovery-agent/evaluate-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsEvaluated").exists())
                .andExpect(jsonPath("$.recommendationCountByAction").exists())
                .andExpect(jsonPath("$.countByPolicyDecision").exists())
                .andExpect(jsonPath("$.averageConfidence").exists());
    }

    private Transaction seedTransaction(TransactionStatus status, BigDecimal amount) {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Agent API Test Merchant")
                .email("agent-api-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Agent API Test Customer")
                .email("agent-api-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(6)
                .failedPaymentCount(0)
                .build());
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_agent_api_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(amount)
                .currency("INR")
                .status(status)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .attemptCount(1)
                .build());
    }
}
