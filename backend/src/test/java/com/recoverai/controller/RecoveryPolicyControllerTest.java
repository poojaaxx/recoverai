package com.recoverai.controller;

import com.recoverai.domain.Customer;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecoveryPolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void evaluateEndpoint_returnsStructuredDecisionOverHttp() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("2499.00"));

        mockMvc.perform(post("/api/recovery-policy/evaluate/{id}", transaction.getId())
                        .contentType("application/json")
                        .content("{\"action\":\"RETRY_PAYMENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getId().toString()))
                .andExpect(jsonPath("$.action").value("RETRY_PAYMENT"))
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.requiresHumanApproval").value(false))
                .andExpect(jsonPath("$.reason").isNotEmpty())
                .andExpect(jsonPath("$.policyChecks").isArray())
                .andExpect(jsonPath("$.policyChecks[0].name").exists());
    }

    @Test
    void evaluateEndpoint_highValueTransaction_returnsEscalate() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("47500.00"));

        mockMvc.perform(post("/api/recovery-policy/evaluate/{id}", transaction.getId())
                        .contentType("application/json")
                        .content("{\"action\":\"RETRY_PAYMENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ESCALATE"))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true));
    }

    @Test
    void evaluateEndpoint_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(post("/api/recovery-policy/evaluate/{id}", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"action\":\"RETRY_PAYMENT\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void evaluateEndpoint_unknownAction_returns400() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("2499.00"));

        mockMvc.perform(post("/api/recovery-policy/evaluate/{id}", transaction.getId())
                        .contentType("application/json")
                        .content("{\"action\":\"NOT_A_REAL_ACTION\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evaluateEndpoint_missingAction_returns400() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("2499.00"));

        mockMvc.perform(post("/api/recovery-policy/evaluate/{id}", transaction.getId())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private Transaction seedTransaction(TransactionStatus status, BigDecimal amount) {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Policy API Test Merchant")
                .email("policy-api-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Policy API Test Customer")
                .email("policy-api-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(5)
                .failedPaymentCount(0)
                .build());
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_policy_api_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(amount)
                .currency("INR")
                .status(status)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode("BANK_DECLINED")
                .attemptCount(1)
                .build());
    }
}
