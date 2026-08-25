package com.recoverai.config;

import com.recoverai.agent.AIRecoveryProvider;
import com.recoverai.agent.AnthropicAIRecoveryProvider;
import com.recoverai.agent.MockAIRecoveryProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14, section 1 - provider selection is exactly one explicit
 * decision point ({@link AIProviderConfig}), driven only by {@code
 * recoverai.ai.provider}. No Spring context needed - the bean factory
 * method is plain, deterministic Java.
 */
class AIProviderConfigTest {

    private final AIProviderConfig config = new AIProviderConfig();
    private final WebClient.Builder webClientBuilder = WebClient.builder();

    @Test
    void mockProvider_isDefault_forMissingOrUnknownValue() {
        RecoveryAgentProperties properties = new RecoveryAgentProperties();
        properties.setProvider("");
        assertThat(config.aiRecoveryProvider(properties, webClientBuilder)).isInstanceOf(MockAIRecoveryProvider.class);

        properties.setProvider("something-unrecognized");
        assertThat(config.aiRecoveryProvider(properties, webClientBuilder)).isInstanceOf(MockAIRecoveryProvider.class);
    }

    @Test
    void mockProvider_selectedExplicitly() {
        RecoveryAgentProperties properties = new RecoveryAgentProperties();
        properties.setProvider("mock");
        assertThat(config.aiRecoveryProvider(properties, webClientBuilder)).isInstanceOf(MockAIRecoveryProvider.class);
    }

    @Test
    void anthropicProvider_selectedByConfiguration_caseInsensitive() {
        RecoveryAgentProperties properties = new RecoveryAgentProperties();
        properties.setProvider("Anthropic");
        AIRecoveryProvider provider = config.aiRecoveryProvider(properties, webClientBuilder);
        assertThat(provider).isInstanceOf(AnthropicAIRecoveryProvider.class);
    }
}
