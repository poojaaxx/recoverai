package com.recoverai.config;

import com.recoverai.agent.AIRecoveryProvider;
import com.recoverai.agent.AnthropicAIRecoveryProvider;
import com.recoverai.agent.GroqAIRecoveryProvider;
import com.recoverai.agent.MockAIRecoveryProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Selects the active {@link AIRecoveryProvider} from {@code
 * recoverai.ai.provider}. Kept as one explicit factory method - rather
 * than {@code @ConditionalOnProperty} on three {@code @Component}-annotated
 * providers - so there is exactly one obvious place that decides which
 * implementation backs the interface, and no risk of an ambiguous-bean
 * wiring error. Defaults to {@link MockAIRecoveryProvider} for any value
 * other than {@code anthropic}/{@code groq} (including a missing/blank
 * property), matching the "provider: mock is always the safe default"
 * requirement.
 */
@Configuration
public class AIProviderConfig {

    @Bean
    public AIRecoveryProvider aiRecoveryProvider(RecoveryAgentProperties properties, WebClient.Builder webClientBuilder) {
        if ("anthropic".equalsIgnoreCase(properties.getProvider())) {
            return new AnthropicAIRecoveryProvider(properties, webClientBuilder.build());
        }
        if ("groq".equalsIgnoreCase(properties.getProvider())) {
            return new GroqAIRecoveryProvider(properties, webClientBuilder.build());
        }
        return new MockAIRecoveryProvider();
    }
}
