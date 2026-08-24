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
class RecoveryExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void executeEndpoint_takesNoBody_clientCannotOverrideAmount() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("2499.00"), 6, 0);

        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getId().toString()))
                .andExpect(jsonPath("$.amount").value(2499.00))
                .andExpect(jsonPath("$.executed").exists())
                .andExpect(jsonPath("$.policyDecision.decision").exists())
                .andExpect(jsonPath("$.duplicate").value(false));
    }

    @Test
    void executeEndpoint_highValueTransaction_notExecuted_requiresHumanApproval() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.FAILED, new BigDecimal("47500.00"), 6, 0);

        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(false))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true))
                .andExpect(jsonPath("$.policyDecision.decision").value("ESCALATE"));
    }

    @Test
    void executeEndpoint_alreadyRecoveredTransaction_notExecuted() throws Exception {
        Transaction transaction = seedTransaction(TransactionStatus.RECOVERED, new BigDecimal("1899.00"), 6, 0);

        mockMvc.perform(post("/api/recovery/{id}/execute", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(false))
                .andExpect(jsonPath("$.policyDecision.decision").value("BLOCK"));
    }

    @Test
    void executeEndpoint_unknownTransaction_returns404() throws Exception {
        mockMvc.perform(post("/api/recovery/{id}/execute", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void noRawPaymentExecutionEndpointExists() throws Exception {
        mockMvc.perform(post("/api/payments/execute"))
                .andExpect(status().isNotFound());
    }

    private Transaction seedTransaction(TransactionStatus status, BigDecimal amount, int successCount, int failedCount) {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Execution API Test Merchant")
                .email("exec-api-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Execution API Test Customer")
                .email("exec-api-cust-" + UUID.randomUUID() + "@example.com")
                .successfulPaymentCount(successCount)
                .failedPaymentCount(failedCount)
                .build());
        return transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_exec_api_" + UUID.randomUUID())
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
