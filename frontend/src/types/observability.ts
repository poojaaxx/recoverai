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
  /** Phase 14 — the actual configured `recoverai.ai.provider` value ("mock" or "anthropic"), read from configuration. Pass to `aiProviderLabel()` (types/recovery.ts) for the honest, judge-facing label. */
  aiProviderMode: string
}
