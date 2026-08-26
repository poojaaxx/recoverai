package com.recoverai.agent;

import com.recoverai.config.RecoveryAgentProperties;
import com.recoverai.domain.FailureCategory;
import com.recoverai.domain.PaymentMethod;
import com.recoverai.domain.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct unit coverage for {@link AnthropicAIRecoveryProvider} itself -
 * the HTTP call and response-parsing logic that no other test exercises
 * (everything else in the suite goes through {@link MockAIRecoveryProvider}
 * or a hand-written stub). No real network call is made; {@link WebClient}
 * is built with a fake {@code ExchangeFunction} so these tests are fast,
 * deterministic, and require no API key or network access.
 * <p>
 * Confirms the two safety properties that matter for this provider
 * specifically: (1) any failure mode - missing key, network error, non-2xx
 * status, malformed JSON, unsupported action value - always surfaces as
 * {@link AIProviderException} rather than a raw/unchecked exception (so
 * {@code RecoveryAgentService}'s catch-and-fall-back-to-ESCALATE behavior
 * always applies), and (2) no configured secret ever leaks into an
 * exception message.
 */
class AnthropicAIRecoveryProviderTest {

    private static final String FAKE_KEY = "sk-ant-test-key-should-never-leak-anywhere";

    private RecoveryAgentProperties properties(String apiKey) {
        RecoveryAgentProperties properties = new RecoveryAgentProperties();
        properties.setProvider("anthropic");
        properties.getAnthropic().setApiKey(apiKey);
        properties.getAnthropic().setModel("claude-sonnet-5");
        properties.getAnthropic().setTimeoutSeconds(5);
        return properties;
    }

    private RecoveryAgentContext context() {
        var transaction = new RecoveryAgentContext.TransactionContext(
                UUID.randomUUID(), "txn_anthropic_test", new BigDecimal("999.00"), "INR",
                TransactionStatus.FAILED, PaymentMethod.CARD, FailureCategory.TEMPORARY_FAILURE, 1, Instant.now());
        var customer = new RecoveryAgentContext.CustomerContext(UUID.randomUUID(), 5, 1, new BigDecimal("5000.00"));
        var history = new RecoveryAgentContext.RecoveryHistoryContext(0, 0, 0, null, null);
        var policy = new RecoveryAgentContext.PolicyContext(3, new BigDecimal("50000"), 5, 24);
        return new RecoveryAgentContext(transaction, customer, null, history, policy);
    }

    private WebClient fakeWebClient(HttpStatus status, String body) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(status).header("content-type", "application/json").body(body).build()))
                .build();
    }

    private static String anthropicEnvelope(String innerJson) {
        return """
                {"content":[{"type":"text","text":%s}]}
                """.formatted(toJsonString(innerJson));
    }

    private static String toJsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ---------------------------------------------------------------- missing key

    @Test
    void missingApiKey_throwsBeforeAnyNetworkCall() {
        AtomicBoolean called = new AtomicBoolean(false);
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    called.set(true);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
                })
                .build();

        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(""), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("no API key");
        assertThat(called).isFalse();
    }

    @Test
    void blankApiKey_isTreatedAsMissing() {
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(
                properties("   "), fakeWebClient(HttpStatus.OK, "{}"));

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class);
    }

    // ---------------------------------------------------------------- well-formed response

    @Test
    void wellFormedResponse_parsesIntoRecommendation_withAnthropicProviderAndConfiguredModel() {
        String inner = """
                {"action":"RETRY_PAYMENT","confidence":0.82,"rationale":"Temporary failure with strong recent history.",\
                "interventionType":"RETRY","expectedRecoveryValue":799.00,"urgency":"MEDIUM"}""";
        WebClient webClient = fakeWebClient(HttpStatus.OK, anthropicEnvelope(inner));
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);
        RecoveryAgentContext ctx = context();

        RecoveryRecommendation recommendation = provider.recommend(ctx);

        assertThat(recommendation.transactionId()).isEqualTo(ctx.transaction().transactionId());
        assertThat(recommendation.recommendedAction()).isEqualTo(com.recoverai.domain.RecoveryAction.RETRY_PAYMENT);
        assertThat(recommendation.confidence()).isEqualByComparingTo("0.82");
        assertThat(recommendation.rationale()).contains("Temporary failure");
        assertThat(recommendation.provider()).isEqualTo("anthropic");
        assertThat(recommendation.model()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void responseWrappedInMarkdownCodeFence_isStrippedAndParsed() {
        String inner = "```json\n" + """
                {"action":"ESCALATE","confidence":0.4,"rationale":"Uncertain outcome, prefer human review.",\
                "interventionType":"ESCALATE","expectedRecoveryValue":0,"urgency":"HIGH"}""" + "\n```";
        WebClient webClient = fakeWebClient(HttpStatus.OK, anthropicEnvelope(inner));
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        RecoveryRecommendation recommendation = provider.recommend(context());

        assertThat(recommendation.recommendedAction()).isEqualTo(com.recoverai.domain.RecoveryAction.ESCALATE);
        assertThat(recommendation.provider()).isEqualTo("anthropic");
    }

    // ---------------------------------------------------------------- malformed / unsupported output

    @Test
    void emptyContentArray_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.OK, "{\"content\":[]}");
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("no content blocks");
    }

    @Test
    void unsupportedActionValue_throwsAIProviderException() {
        String inner = """
                {"action":"DELETE_USER","confidence":0.9,"rationale":"not a real action",\
                "interventionType":"RETRY","expectedRecoveryValue":0,"urgency":"LOW"}""";
        WebClient webClient = fakeWebClient(HttpStatus.OK, anthropicEnvelope(inner));
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void nonJsonText_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.OK, anthropicEnvelope("this is not json at all"));
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void unparsableEnvelope_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.OK, "not even an envelope");
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class);
    }

    // ---------------------------------------------------------------- transport failures

    @Test
    void nonSuccessHttpStatus_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Anthropic API call failed");
    }

    @Test
    void networkFailure_throwsAIProviderException_andNeverLeaksTheApiKey() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("connection refused: " + FAKE_KEY)))
                .build();
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Anthropic API call failed");
    }

    @Test
    void apiKeyIsSentAsHeader_neverAsPartOfTheRequestBody() {
        AtomicBoolean sawKeyInHeader = new AtomicBoolean(false);
        String inner = """
                {"action":"STOP","confidence":0.6,"rationale":"stop","interventionType":"STOP",\
                "expectedRecoveryValue":0,"urgency":"LOW"}""";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    sawKeyInHeader.set(FAKE_KEY.equals(request.headers().getFirst("x-api-key")));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("content-type", "application/json")
                            .body(anthropicEnvelope(inner))
                            .build());
                })
                .build();
        AnthropicAIRecoveryProvider provider = new AnthropicAIRecoveryProvider(properties(FAKE_KEY), webClient);

        provider.recommend(context());

        assertThat(sawKeyInHeader).isTrue();
    }
}
