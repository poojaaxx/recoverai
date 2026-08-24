package com.recoverai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Real AI inference via Anthropic's Messages API - selected by setting
 * {@code recoverai.ai.provider=anthropic} with {@code ANTHROPIC_API_KEY}
 * configured. The AI still only recommends; {@code
 * com.recoverai.policy.RecoveryPolicyService} remains the sole
 * authorization boundary regardless of what this provider returns.
 * <p>
 * <b>Unverified in this environment</b> - no Anthropic API key was
 * available while building this project, so this class has never been
 * exercised against the real API (see the Phase 5 report's "Known
 * limitations"). It is written defensively for exactly that reason: any
 * network error, non-2xx response, timeout, or response that does not
 * parse into the exact recommendation schema throws {@link
 * AIProviderException}, which {@code RecoveryAgentService} always catches
 * and turns into a safe fallback recommendation - a bug here degrades to
 * "AI unavailable," it cannot break the endpoint or bypass policy.
 */
public class AnthropicAIRecoveryProvider implements AIRecoveryProvider {

    private static final String PROVIDER_NAME = "anthropic";
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** No chain-of-thought is requested or stored - only the final structured schema, with a concise rationale field. */
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicAIRecoveryProvider(RecoveryAgentProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public RecoveryRecommendation recommend(RecoveryAgentContext context) {
        RecoveryAgentProperties.Anthropic config = properties.getAnthropic();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new AIProviderException("Anthropic provider selected but no API key is configured (ANTHROPIC_API_KEY).");
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", config.getModel(),
                    "max_tokens", config.getMaxTokens(),
                    "temperature", config.getTemperature(),
                    "system", SYSTEM_PROMPT,
                    "messages", List.of(Map.of("role", "user", "content", buildUserContent(context)))
            );

            String responseBody = webClient.post()
                    .uri(API_URL)
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(config.getTimeoutSeconds()));

            return parseRecommendation(context, responseBody, config.getModel());
        } catch (AIProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new AIProviderException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    /** The only data sent to the model - transaction/customer/risk/history facts already scoped down by RecoveryAgentContext. No secrets, no unrelated PII. */
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
            JsonNode contentArray = root.path("content");
            if (!contentArray.isArray() || contentArray.isEmpty()) {
                throw new AIProviderException("Anthropic response had no content blocks.");
            }
            String text = contentArray.get(0).path("text").asText("");
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
            throw new AIProviderException("Failed to parse Anthropic response into the recommendation schema: " + e.getMessage(), e);
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
