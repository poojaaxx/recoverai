export interface PolicyDecisionCounts {
  allow: number
  block: number
  escalate: number
  stop: number
}

export interface WebhookCounts {
  receivedTotal: number
  processed: number
  rejected: number
  ignored: number
  invalidSignature: number
  malformedPayload: number
}

export interface ProviderCounts {
  provider: string
  status: string
  total: number
}

export interface ObservabilityMetrics {
  policyDecisions: PolicyDecisionCounts
  webhooks: WebhookCounts
  providers: ProviderCounts[]
  /** The actual configured `recoverai.ai.provider` value ("mock", "anthropic", or "groq"), read from configuration. Pass to `aiProviderLabel()` (types/recovery.ts) for the honest, judge-facing label. */
  aiProviderMode: string
  /** The configured model for whichever provider is active — null for "mock". Always reflects configuration, not any single call's outcome (a failed call still reports the same configured provider/model here). */
  aiModel: string | null
}
