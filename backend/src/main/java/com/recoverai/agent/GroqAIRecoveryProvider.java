package com.recoverai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.recoverai.config.RecoveryAgentProperties;
import com.recoverai.domain.InterventionType;
import com.recoverai.domain.RecoveryAction;
import com.recoverai.domain.Urgency;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Real AI inference via Groq's OpenAI-compatible chat/completions API -
 * selected by setting {@code recoverai.ai.provider=groq} with {@code
 * GROQ_API_KEY} configured. Structurally identical to {@link
 * AnthropicAIRecoveryProvider}: same recommendation-only contract, same
 * validated JSON schema, same fail-closed error handling - only the
 * transport (OpenAI-shaped chat/completions request/response instead of
 * Anthropic's Messages API) differs. The AI still only recommends; {@code
 * com.recoverai.policy.RecoveryPolicyService} remains the sole
 * authorization boundary regardless of what this provider returns.
 * <p>
 * <b>Unverified against the live API</b> - no Groq API key has been
 * available in any environment this project has run in, so a real request
 * has never been made. The HTTP call and response-parsing logic here are
 * directly unit-tested with a fake {@link WebClient} {@code
 * ExchangeFunction} instead (see {@code GroqAIRecoveryProviderTest}) -
 * well-formed and malformed responses, missing/blank key, non-2xx status,
 * timeout, and network failure. Any such failure throws {@link
 * AIProviderException}, which {@code RecoveryAgentService} always catches
 * and turns into a safe fallback recommendation - a bug here degrades to
 * "AI unavailable," it cannot break the endpoint or bypass policy.
 */
public class GroqAIRecoveryProvider implements AIRecoveryProvider {

    private static final String PROVIDER_NAME = "groq";
    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    /** Same recommendation contract as {@link AnthropicAIRecoveryProvider}'s system prompt - no chain-of-thought, one structured JSON object. */
    private static final String SYSTEM_PROMPT = """
            You are a revenue recovery decision agent for a payments platform.

            Your job is to select the single most appropriate recovery
            intervention based on the supplied transaction, customer,
            revenue-risk, and recovery-history context. Treat that context
            as authoritative and complete - never invent facts beyond it
            (no fabricated history, no claim that a payment already
            succeeded or that money was already recovered).

            You must:
            - optimize for expected recoverable revenue
            - consider customer history, failure category, and prior recovery attempts
            - avoid recommending unnecessary repeated actions
            - prefer the safer intervention when uncertain
            - recommend escalation when human review is appropriate (e.g. high uncertainty, critical risk)
            - recommend stopping when recovery should cease (e.g. repeated failures, already halted)
            - never reason about or attempt to override safety/policy limits - a separate deterministic system enforces those regardless of your recommendation

            Respond with ONLY a single JSON object matching this exact schema - no prose, no markdown code fences, no chain-of-thought:

            {
              "action": "RETRY_PAYMENT" | "CREATE_PAYMENT_LINK" | "SEND_RECOVERY_REMINDER" | "ESCALATE" | "STOP",
              "confidence": <number, 0.0 to 1.0>,
              "rationale": "<concise one-to-two sentence explanation>",
              "interventionType": "RETRY" | "REENGAGE" | "ESCALATE" | "STOP",
              "expectedRecoveryValue": <number, >= 0>,
              "urgency": "LOW" | "MEDIUM" | "HIGH"
            }
            """;

    private final RecoveryAgentProperties properties;
    private final WebClient webClient;
    /** See {@link AnthropicAIRecoveryProvider}'s identical field javadoc - a plain {@code new ObjectMapper()} needs JSR-310 registered to serialize this provider's {@code Instant}-bearing context. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public GroqAIRecoveryProvider(RecoveryAgentProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public RecoveryRecommendation recommend(RecoveryAgentContext context) {
        RecoveryAgentProperties.Groq config = properties.getGroq();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new AIProviderException("Groq provider selected but no API key is configured (GROQ_API_KEY).");
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", config.getModel(),
                    "max_tokens", config.getMaxTokens(),
                    "temperature", config.getTemperature(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", buildUserContent(context)))
            );

            String responseBody = webClient.post()
                    .uri(API_URL)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("content-type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(config.getTimeoutSeconds()));

            return parseRecommendation(context, responseBody, config.getModel());
        } catch (AIProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AIProviderException("Groq API call failed: " + e.getMessage(), e);
        }
    }

    /** Same scoped-down, secret-free context Anthropic receives - see {@link AnthropicAIRecoveryProvider#buildUserContent}. */
    private String buildUserContent(RecoveryAgentContext context) {
        try {
            return "Evaluate this recovery context and return the recommendation JSON:\n" + objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new AIProviderException("Failed to serialize recovery context: " + e.getMessage(), e);
        }
    }

    private RecoveryRecommendation parseRecommendation(RecoveryAgentContext context, String responseBody, String model) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AIProviderException("Groq response had no choices.");
            }
            String text = choices.get(0).path("message").path("content").asText("");
            JsonNode rec = objectMapper.readTree(stripCodeFences(text));

            RecoveryAction action = RecoveryAction.valueOf(rec.path("action").asText());
            InterventionType interventionType = InterventionType.valueOf(rec.path("interventionType").asText());
            Urgency urgency = Urgency.valueOf(rec.path("urgency").asText());
            BigDecimal confidence = new BigDecimal(rec.path("confidence").asText());
            BigDecimal expectedRecoveryValue = new BigDecimal(rec.path("expectedRecoveryValue").asText());
            String rationale = rec.path("rationale").asText("");

            return new RecoveryRecommendation(context.transaction().transactionId(), action, confidence, rationale,
                    interventionType, expectedRecoveryValue, urgency, PROVIDER_NAME, model);
        } catch (AIProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AIProviderException("Failed to parse Groq response into the recommendation schema: " + e.getMessage(), e);
        }
    }

    private static String stripCodeFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
