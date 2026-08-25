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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code @WithMockUser} authenticates every request here as MERCHANT_ADMIN - authentication/authorization itself is covered by {@code AuthenticationIntegrationTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test-admin", roles = "MERCHANT_ADMIN")
class RevenueRiskControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void analyzeGetMetricsAndListEndpointsWorkOverHttp() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("API Test Merchant")
                .email("api-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("API Test Customer")
                .email("api-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(6)
                .failedPaymentCount(0)
                .build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_api_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("2499.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.CARD)
                .failureCode(FailureCategory.TEMPORARY_FAILURE.name())
                .attemptCount(1)
                .build());

        mockMvc.perform(get("/api/revenue-risk/{id}", transaction.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/revenue-risk/analyze/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getId().toString()))
                .andExpect(jsonPath("$.amountAtRisk").value(2499.00))
                .andExpect(jsonPath("$.riskLevel").exists())
                .andExpect(jsonPath("$.factors").isArray())
                .andExpect(jsonPath("$.reason").isNotEmpty());

        mockMvc.perform(get("/api/revenue-risk/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getId().toString()));

        mockMvc.perform(post("/api/revenue-risk/analyze/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/revenue-risk/analyze-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionsAnalyzed").exists())
                .andExpect(jsonPath("$.metrics.revenueAtRisk").exists());

        mockMvc.perform(get("/api/revenue-risk/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTransactions").exists())
                .andExpect(jsonPath("$.potentiallyRecoverableRevenue").exists());

        mockMvc.perform(get("/api/revenue-risk").param("riskLevel", "LOW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
