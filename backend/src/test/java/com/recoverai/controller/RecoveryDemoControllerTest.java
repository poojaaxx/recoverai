package com.recoverai.controller;

import com.recoverai.seed.DemoDataSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecoveryDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder.seed();
    }

    @Test
    void getAll_returnsFiveScenariosAndAggregateMetrics() throws Exception {
        mockMvc.perform(get("/api/demo/recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenariosEvaluated").value(5))
                .andExpect(jsonPath("$.scenarios.length()").value(5))
                .andExpect(jsonPath("$.confirmedAmountRecovered").value(0.00))
                .andExpect(jsonPath("$.executedCount").value(1))
                .andExpect(jsonPath("$.gatewayCalls").value(1));
    }

    @Test
    void getOne_easyRecovery_returnsExecutedScenario() throws Exception {
        mockMvc.perform(get("/api/demo/recovery/{externalTransactionId}", "demo-easy-recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioLabel").value("EASY_RECOVERY"))
                .andExpect(jsonPath("$.policyDecision").value("ALLOW"))
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.amountRecovered").value(0.00))
                .andExpect(jsonPath("$.auditTimeline").isArray());
    }

    @Test
    void getOne_highValue_returnsEscalatedNotExecutedScenario() throws Exception {
        mockMvc.perform(get("/api/demo/recovery/{externalTransactionId}", "demo-high-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyDecision").value("ESCALATE"))
                .andExpect(jsonPath("$.executed").value(false))
                .andExpect(jsonPath("$.requiresHumanApproval").value(true));
    }

    @Test
    void getOne_unknownScenario_returns404() throws Exception {
        mockMvc.perform(get("/api/demo/recovery/{externalTransactionId}", "not-a-real-demo-transaction"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAll_doesNotExposeAnySecretLikeFields() throws Exception {
        mockMvc.perform(get("/api/demo/recovery"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString().toLowerCase();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .doesNotContain("apikey")
                            .doesNotContain("api_key")
                            .doesNotContain("secret")
                            .doesNotContain("rzp_live");
                });
    }
}
