package com.recoverai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * AI provider selection and configuration for the Phase 5 recovery agent.
 * The default provider is {@code mock} - deterministic, offline, requires
 * no API key - so the application builds, runs, and tests fully without
 * any AI credentials. Real credentials are read from environment
 * variables only (see {@code application.yml}); nothing here is ever a
 * literal secret value.
 */
@Component
@ConfigurationProperties(prefix = "recoverai.ai")
@Getter
@Setter
public class RecoveryAgentProperties {

    /** {@code mock} (default) or {@code anthropic}. */
    private String provider = "mock";

    private Anthropic anthropic = new Anthropic();

    @Getter
    @Setter
    public static class Anthropic {
        private String apiKey = "";
        private String model = "claude-sonnet-5";
        private BigDecimal temperature = new BigDecimal("0.2");
        private int timeoutSeconds = 15;
        private int maxTokens = 1024;
    }
}
