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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code @WithMockUser} authenticates every request here as MERCHANT_ADMIN - authentication/authorization itself is covered by {@code AuthenticationIntegrationTest}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test-admin", roles = "MERCHANT_ADMIN")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MerchantRepository merchantRepository;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void listAndGetByIdReturnPersistedTransactions() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Controller Test Merchant")
                .email("controller-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Controller Test Customer")
                .email("customer-" + UUID.randomUUID() + "@example.com")
                .build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_controller_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.UPI)
                .attemptCount(1)
                .build());

        mockMvc.perform(get("/api/transactions").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        mockMvc.perform(get("/api/transactions/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalTransactionId").value(transaction.getExternalTransactionId()))
                .andExpect(jsonPath("$.customerName").value("Controller Test Customer"))
                .andExpect(jsonPath("$.status").value("FAILED"));

        mockMvc.perform(get("/api/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_customerEmailIsMasked_notReturnedInFull() throws Exception {
        Merchant merchant = merchantRepository.save(Merchant.builder()
                .name("Masking Test Merchant")
                .email("masking-merchant-" + UUID.randomUUID() + "@example.com")
                .build());
        Customer customer = customerRepository.save(Customer.builder()
                .merchant(merchant)
                .name("Masking Test Customer")
                .email("janedoe@example.com")
                .build());
        Transaction transaction = transactionRepository.save(Transaction.builder()
                .externalTransactionId("txn_masking_" + UUID.randomUUID())
                .merchant(merchant)
                .customer(customer)
                .amount(new BigDecimal("500.00"))
                .currency("INR")
                .status(TransactionStatus.FAILED)
                .paymentMethod(PaymentMethod.UPI)
                .attemptCount(1)
                .build());

        mockMvc.perform(get("/api/transactions/{id}", transaction.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerEmail").value("j***e@example.com"))
                .andExpect(jsonPath("$.customerEmail", not("janedoe@example.com")));
    }

    @Test
    void getById_malformedUuid_returnsNormalizedBadRequest() throws Exception {
        mockMvc.perform(get("/api/transactions/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
