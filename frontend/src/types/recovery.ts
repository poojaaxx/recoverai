import type { AuditTimelineEntry, PaymentConfirmationStatus, PolicyDecision, RecoveryAction, RiskLevel } from './demo'

export interface HealthStatus {
  status: string
  service: string
  timestamp: string
}

export interface TransactionDetail {
  id: string
  externalTransactionId: string
  merchantId: string
  customerId: string
  customerName: string
  customerEmail: string
  amount: number
  currency: string
  status: string
  paymentMethod: string
  failureCode: string | null
  failureReason: string | null
  attemptCount: number
  createdAt: string
  updatedAt: string
}

export interface RiskAnalysis {
  transactionId: string
  externalTransactionId: string
  amount: number
  currency: string
  amountAtRisk: number
  riskScore: number
  riskLevel: RiskLevel
  recoveryProbability: number
  potentialRecoveryValue: number
  factors: string[]
  reason: string
  analyzedAt: string
}

export interface PolicyCheck {
  name: string
  passed: boolean
  reason: string
}

export interface PolicyDecisionResult {
  transactionId: string
  externalTransactionId: string
  action: RecoveryAction
  decision: PolicyDecision
  requiresHumanApproval: boolean
  reason: string
  policyChecks: PolicyCheck[]
  evaluatedAt: string
}

export interface AiRecommendation {
  action: RecoveryAction
  confidence: number
  rationale: string
  interventionType: string
  expectedRecoveryValue: number
  urgency: string
  provider: string
  model: string | null
  providerAvailable: boolean
}

/**
 * P0.3 — honest, judge-facing labels for the AI provider that actually
 * produced a recommendation. `mock` is this project's deterministic,
 * offline decision engine (the default everywhere, including this
 * deployment) — it is never presented as a live LLM. `anthropic` and
 * `groq` are real, live LLM integrations, only active when explicitly
 * configured with credentials. `fallback` is what a per-recommendation
 * `provider` field reports when the configured provider's call actually
 * failed (missing key, network error, timeout, malformed output) and
 * `RecoveryAgentService` substituted its safe ESCALATE default — this is
 * intentionally a distinct label from the provider name itself, so a
 * failed Groq/Anthropic call is never shown as if it had produced a real
 * recommendation. Pass `model` when known (e.g. from `ObservabilityMetrics.
 * aiModel` or a recommendation's own `model` field) to name exactly which
 * model is configured/was used.
 */
export function aiProviderLabel(provider: string, model?: string | null): string {
  switch (provider) {
    case 'mock':
      return 'Deterministic AI simulation — no external LLM configured'
    case 'anthropic':
      return model ? `Anthropic Claude — ${model}` : 'Anthropic Claude'
    case 'groq':
      return model ? `Groq — ${model}` : 'Groq'
    case 'fallback':
      return 'AI unavailable — escalated automatically'
    default:
      return provider
  }
}

/** Response of `POST /api/recovery-agent/evaluate/{id}` — the AI's recommendation AND the policy's decision on it, from one real backend call. */
export interface AgentEvaluation {
  transactionId: string
  externalTransactionId: string
  aiRecommendation: AiRecommendation
  policyDecision: PolicyDecisionResult
  finalAction: RecoveryAction | null
  requiresHumanApproval: boolean
  expectedRecoveryValue: number
  auditEventId: string | null
  evaluatedAt: string
}

/** Response of `POST /api/recovery/{id}/execute`. `amountRecovered` is a confirmed-recovery figure only — stays 0 for every simulated/mock execution. */
export interface ExecutionResult {
  transactionId: string
  externalTransactionId: string
  recommendation: AiRecommendation | null
  policyDecision: PolicyDecisionResult | null
  requiresHumanApproval: boolean
  executed: boolean
  recoveryAttemptId: string | null
  action: RecoveryAction | null
  provider: string | null
  providerReference: string | null
  executionStatus: string | null
  amount: number
  amountRecovered: number
  simulated: boolean
  failureCode: string | null
  failureReason: string | null
  duplicate: boolean
  executionNote: string | null
  auditEventId: string | null
  executedAt: string
  /** Strictly separate from executionStatus - SUCCESS only means the provider call went through; CONFIRMED means a verified webhook proved the customer actually paid. */
  paymentConfirmationStatus: PaymentConfirmationStatus
  confirmedAmount: number | null
  confirmedCurrency: string | null
  providerPaymentId: string | null
  confirmedAt: string | null
}

export interface RiskMetrics {
  totalTransactions: number
  atRiskTransactions: number
  totalTransactionValue: number
  totalRevenueCollected: number
  revenueAtRisk: number
  highRiskRevenue: number
  criticalRiskRevenue: number
  averageRecoveryProbability: number
  potentiallyRecoverableRevenue: number
}

export type AuditEntry = AuditTimelineEntry

/** Response of `POST /api/demo/recovery/confirm-test-payment/{id}` (P0.4). `label` always states this is a TEST/SIMULATION, never a real Razorpay payment — display it verbatim, never abbreviate it away. */
export interface TestPaymentConfirmation {
  label: string
  outcome: string
  reason: string
  recoveryAttemptId: string | null
  transactionId: string
  confirmedAmount: number | null
  confirmedCurrency: string | null
}

/** One row of `GET /api/audit` (P1.4) — the portfolio-wide audit feed, distinct from the per-transaction timeline only in that it also identifies which transaction each event belongs to. */
export interface GlobalAuditEntry {
  id: string
  transactionId: string
  externalTransactionId: string
  eventType: string
  actor: string
  decision: string | null
  reason: string | null
  timestamp: string
}

export interface GlobalAuditFilters {
  eventType?: string
  actor?: string
  transactionId?: string
  page?: number
  size?: number
}

/** Matches Spring Data's `Page<T>` JSON shape — same convention `TransactionListPage` already uses. */
export interface GlobalAuditPage {
  content: GlobalAuditEntry[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** Response of `POST /api/revenue-risk/analyze-all`. */
export interface BatchRiskAnalysisResult {
  transactionsAnalyzed: number
  metrics: RiskMetrics
}

/** Response of `POST /api/recovery-agent/evaluate-all`. Recommendation/decision counts only — no execution happens in this batch call. */
export interface BatchAgentEvaluationResult {
  transactionsEvaluated: number
  recommendationCountByAction: Record<string, number>
  countByPolicyDecision: Record<string, number>
  averageConfidence: number
  providerFailures: number
  malformedOutputs: number
}

/** Response of `GET /api/recovery/metrics`. `confirmedRecoveredRevenue` is the only figure this app ever calls "recovered" — summed only from verified webhook confirmations. */
export interface RecoveryMetrics {
  totalRevenueAtRisk: number
  potentiallyRecoverableRevenue: number
  recoveryAttempts: number
  successfulExecutionCount: number
  confirmedRecoveryCount: number
  confirmedRecoveredRevenue: number
  recoveryRate: number
  executionSuccessRate: number
  confirmationRate: number
  pendingConfirmationAmount: number
  amountRemainingAtRisk: number
  transactionsRecovered: number
  transactionsEscalated: number
  transactionsStopped: number
  /** Phase 14 — distinct customers with at least one recovery attempt, i.e. customers the system has actually acted on. */
  distinctCustomersProcessed: number
}

/** Phase 14, section 2/5 — bounded batch recovery execution. `transactionIds` is the only thing a client controls; amount/action/authorization are always server-derived. */
export interface BatchExecutionRequest {
  transactionIds: string[]
}

export type BatchExecutionOutcome =
  | 'EXECUTED'
  | 'FAILED_PROVIDER_CALL'
  | 'ALREADY_EXECUTED'
  | 'BLOCKED'
  | 'ESCALATED'
  | 'STOPPED'
  | 'SKIPPED_PORTFOLIO_LIMIT'
  | 'NOT_FOUND'

export interface BatchExecutionItemResult {
  transactionId: string
  externalTransactionId: string | null
  outcome: BatchExecutionOutcome
  policyDecision: PolicyDecision | null
  finalAction: RecoveryAction | null
  recoveryAttemptId: string | null
  amount: number | null
  reason: string | null
}

/** Response of `POST /api/recovery/batch/execute`. `executedCount` is a provider-execution figure, never confirmed revenue — only a subsequent webhook confirmation can report that. */
export interface BatchExecutionResponse {
  totalRequested: number
  distinctCount: number
  duplicateRequestCount: number
  executedCount: number
  failedProviderCallCount: number
  alreadyExecutedCount: number
  blockedCount: number
  escalatedCount: number
  stoppedCount: number
  skippedPortfolioLimitCount: number
  notFoundCount: number
  aggregateAmountExecuted: number
  maxAggregateAmount: number
  maxTransactionCount: number
  results: BatchExecutionItemResult[]
}
