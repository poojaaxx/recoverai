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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct unit coverage for {@link GroqAIRecoveryProvider} - the same
 * discipline as {@code AnthropicAIRecoveryProviderTest}, adapted for
 * Groq's OpenAI-compatible {@code chat/completions} response shape ({@code
 * choices[0].message.content} instead of Anthropic's {@code
 * content[0].text}). No real network call is made; {@link WebClient} is
 * built with a fake {@code ExchangeFunction}, so these tests are fast,
 * deterministic, and require no API key or network access.
 * <p>
 * Confirms the same two safety properties: (1) every failure mode -
 * missing key, network error, timeout, non-2xx status, malformed JSON,
 * unsupported action - surfaces as {@link AIProviderException} rather than
 * a raw/unchecked exception, so {@code RecoveryAgentService}'s
 * catch-and-fall-back-to-ESCALATE behavior always applies, and (2) no
 * configured secret ever leaks into an exception message or anywhere but
 * the {@code Authorization} header.
 */
class GroqAIRecoveryProviderTest {

    private static final String FAKE_KEY = "gsk_test-key-should-never-leak-anywhere";

    private RecoveryAgentProperties properties(String apiKey) {
        RecoveryAgentProperties properties = new RecoveryAgentProperties();
        properties.setProvider("groq");
        properties.getGroq().setApiKey(apiKey);
        properties.getGroq().setModel("llama-3.3-70b-versatile");
        properties.getGroq().setTimeoutSeconds(5);
        return properties;
    }

    private RecoveryAgentContext context() {
        var transaction = new RecoveryAgentContext.TransactionContext(
                UUID.randomUUID(), "txn_groq_test", new BigDecimal("999.00"), "INR",
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

    private static String groqEnvelope(String innerJson) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":%s}}]}
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

        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(""), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("no API key");
        assertThat(called).isFalse();
    }

    @Test
    void blankApiKey_isTreatedAsMissing() {
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(
                properties("   "), fakeWebClient(HttpStatus.OK, "{}"));

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class);
    }

    // ---------------------------------------------------------------- well-formed response

    @Test
    void wellFormedResponse_parsesIntoRecommendation_withGroqProviderAndConfiguredModel() {
        String inner = """
                {"action":"RETRY_PAYMENT","confidence":0.82,"rationale":"Temporary failure with strong recent history.",\
                "interventionType":"RETRY","expectedRecoveryValue":799.00,"urgency":"MEDIUM"}""";
        WebClient webClient = fakeWebClient(HttpStatus.OK, groqEnvelope(inner));
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);
        RecoveryAgentContext ctx = context();

        RecoveryRecommendation recommendation = provider.recommend(ctx);

        assertThat(recommendation.transactionId()).isEqualTo(ctx.transaction().transactionId());
        assertThat(recommendation.recommendedAction()).isEqualTo(com.recoverai.domain.RecoveryAction.RETRY_PAYMENT);
        assertThat(recommendation.confidence()).isEqualByComparingTo("0.82");
        assertThat(recommendation.rationale()).contains("Temporary failure");
        assertThat(recommendation.provider()).isEqualTo("groq");
        assertThat(recommendation.model()).isEqualTo("llama-3.3-70b-versatile");
    }

    @Test
    void responseWrappedInMarkdownCodeFence_isStrippedAndParsed() {
        String inner = "```json\n" + """
                {"action":"ESCALATE","confidence":0.4,"rationale":"Uncertain outcome, prefer human review.",\
                "interventionType":"ESCALATE","expectedRecoveryValue":0,"urgency":"HIGH"}""" + "\n```";
        WebClient webClient = fakeWebClient(HttpStatus.OK, groqEnvelope(inner));
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        RecoveryRecommendation recommendation = provider.recommend(context());

        assertThat(recommendation.recommendedAction()).isEqualTo(com.recoverai.domain.RecoveryAction.ESCALATE);
        assertThat(recommendation.provider()).isEqualTo("groq");
    }

    // ---------------------------------------------------------------- malformed / unsupported output

    @Test
    void emptyChoicesArray_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.OK, "{\"choices\":[]}");
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("no choices");
    }

    @Test
    void unsupportedActionValue_throwsAIProviderException() {
        String inner = """
                {"action":"DELETE_USER","confidence":0.9,"rationale":"not a real action",\
                "interventionType":"RETRY","expectedRecoveryValue":0,"urgency":"LOW"}""";
        WebClient webClient = fakeWebClient(HttpStatus.OK, groqEnvelope(inner));
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void nonJsonText_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.OK, groqEnvelope("this is not json at all"));
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    void unparsableEnvelope_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.OK, "not even an envelope");
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class);
    }

    // ---------------------------------------------------------------- transport failures

    @Test
    void httpBadRequest_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid model\"}");
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Groq API call failed");
    }

    @Test
    void httpServerError_throwsAIProviderException() {
        WebClient webClient = fakeWebClient(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Groq API call failed");
    }

    @Test
    void networkFailure_throwsAIProviderException_andNeverLeaksTheApiKey() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new RuntimeException("connection refused: " + FAKE_KEY)))
                .build();
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Groq API call failed");
    }

    @Test
    void timeout_throwsAIProviderException() {
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header("content-type", "application/json")
                                .body(groqEnvelope("""
                                        {"action":"STOP","confidence":0.5,"rationale":"stop","interventionType":"STOP",\
                                        "expectedRecoveryValue":0,"urgency":"LOW"}"""))
                                .build())
                        .delayElement(Duration.ofMillis(200)))
                .build();
        RecoveryAgentProperties properties = properties(FAKE_KEY);
        properties.getGroq().setTimeoutSeconds(0); // block(Duration.ZERO) always times out
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties, webClient);

        assertThatThrownBy(() -> provider.recommend(context()))
                .isInstanceOf(AIProviderException.class)
                .hasMessageContaining("Groq API call failed");
    }

    // ---------------------------------------------------------------- key handling

    @Test
    void apiKeyIsSentAsAuthorizationHeader_neverAsPartOfTheRequestBody() {
        AtomicBoolean sawKeyInHeader = new AtomicBoolean(false);
        String inner = """
                {"action":"STOP","confidence":0.6,"rationale":"stop","interventionType":"STOP",\
                "expectedRecoveryValue":0,"urgency":"LOW"}""";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    sawKeyInHeader.set(("Bearer " + FAKE_KEY).equals(request.headers().getFirst("Authorization")));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("content-type", "application/json")
                            .body(groqEnvelope(inner))
                            .build());
                })
                .build();
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        provider.recommend(context());

        assertThat(sawKeyInHeader).isTrue();
    }

    @Test
    void requestTargetsTheGroqOpenAiCompatibleEndpoint() {
        AtomicBoolean sawCorrectUri = new AtomicBoolean(false);
        String inner = """
                {"action":"STOP","confidence":0.6,"rationale":"stop","interventionType":"STOP",\
                "expectedRecoveryValue":0,"urgency":"LOW"}""";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    sawCorrectUri.set("https://api.groq.com/openai/v1/chat/completions".equals(request.url().toString()));
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("content-type", "application/json")
                            .body(groqEnvelope(inner))
                            .build());
                })
                .build();
        GroqAIRecoveryProvider provider = new GroqAIRecoveryProvider(properties(FAKE_KEY), webClient);

        provider.recommend(context());

        assertThat(sawCorrectUri).isTrue();
    }
}
