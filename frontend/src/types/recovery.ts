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
}
