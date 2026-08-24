package com.recoverai.agent;

/**
 * Provider abstraction for recovery-recommendation reasoning. Business
 * logic ({@code RecoveryAgentService}) depends only on this interface,
 * never on a specific AI vendor - see {@code MockAIRecoveryProvider}
 * (default, deterministic, offline) and {@code AnthropicAIRecoveryProvider}
 * (real inference, selected via {@code recoverai.ai.provider=anthropic}).
 * <p>
 * Implementations may throw on failure (network error, timeout, malformed
 * response) - {@code RecoveryAgentService} always catches this and fails
 * closed to a safe fallback recommendation; a provider must never let a
 * failure propagate into an authorization decision.
 */
public interface AIRecoveryProvider {

    RecoveryRecommendation recommend(RecoveryAgentContext context);
}
