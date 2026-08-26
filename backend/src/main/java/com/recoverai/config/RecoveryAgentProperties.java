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

    /** {@code mock} (default), {@code anthropic}, or {@code groq}. */
    private String provider = "mock";

    private Anthropic anthropic = new Anthropic();
    private Groq groq = new Groq();

    @Getter
    @Setter
    public static class Anthropic {
        private String apiKey = "";
        private String model = "claude-sonnet-5";
        private BigDecimal temperature = new BigDecimal("0.2");
        private int timeoutSeconds = 15;
        private int maxTokens = 1024;
    }

    /** Groq's OpenAI-compatible chat/completions API - same shape as {@link Anthropic}, different transport. */
    @Getter
    @Setter
    public static class Groq {
        private String apiKey = "";
        private String model = "llama-3.3-70b-versatile";
        private BigDecimal temperature = new BigDecimal("0.2");
        private int timeoutSeconds = 15;
        private int maxTokens = 1024;
    }
}
