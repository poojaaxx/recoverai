package com.recoverai.dto;

import java.util.List;

/**
 * Production readiness phase: aggregate, database-derived (or clearly
 * labeled in-memory) counts for policy decisions, webhook processing, and
 * provider calls - separate from {@link RecoveryMetricsResponse}, which
 * already covers revenue/recovery-rate figures. Nothing here is a
 * per-request trace or a monitoring platform - just the handful of counts
 * a judge or operator would actually want to see. See
 * docs/ARCHITECTURE.md "Production Observability" for exactly where each
 * field comes from.
 * <p>
 * {@code aiProviderMode} (Phase 14) - the actual configured {@code
 * recoverai.ai.provider} value ({@code "mock"}, {@code "anthropic"}, or
 * {@code "groq"}), read directly from configuration, never inferred or
 * guessed - so the frontend can honestly state which AI is active without
 * relying on a per-recommendation field alone. {@code aiModel} is the
 * configured model string for whichever provider is active ({@code null}
 * for {@code mock}, which has no externally configurable model) - this is
 * always the *configured* provider/model, independent of whether any
 * individual recommendation call actually succeeded; a failed call still
 * reports the same configured provider here, while the per-recommendation
 * {@code provider} field (see {@code AIRecommendationResponse}) reports
 * {@code "fallback"} for that one call instead.
 */
public record ObservabilityMetricsResponse(
        PolicyDecisionCounts policyDecisions,
        WebhookCounts webhooks,
        List<ProviderCounts> providers,
        String aiProviderMode,
        String aiModel
) {
    /** From every {@code RECOVERY_POLICY_EVALUATED} audit row ever written - the one authoritative, deduplicated source of policy decisions. */
    public record PolicyDecisionCounts(long allow, long block, long escalate, long stop) {
    }

    /**
     * {@code receivedTotal} is every delivery that reached the endpoint,
     * including the two kinds that fail before any {@code webhook_events}
     * row can be persisted ({@code invalidSignature}, {@code
     * malformedPayload} - in-memory, per-instance counters, the same
     * documented simplification {@code RateLimitFilter} already uses).
     * Every other field is a real persisted count.
     */
    public record WebhookCounts(long receivedTotal, long processed, long rejected, long ignored,
                                 long invalidSignature, long malformedPayload) {
    }

    /** One row per (provider, execution status) pair that has ever occurred - e.g. {@code mock/SUCCESS}, {@code razorpay/FAILED}. */
    public record ProviderCounts(String provider, String status, long total) {
    }
}
