export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type PolicyDecision = 'ALLOW' | 'BLOCK' | 'ESCALATE' | 'STOP'
export type RecoveryAction =
  | 'RETRY_PAYMENT'
  | 'CREATE_PAYMENT_LINK'
  | 'SEND_RECOVERY_REMINDER'
  | 'ESCALATE'
  | 'STOP'
export type RecoveryAttemptStatus = 'PENDING' | 'SUCCESS' | 'FAILED' | 'BLOCKED' | 'ESCALATED'
export type PaymentConfirmationStatus = 'NOT_CONFIRMED' | 'CONFIRMED' | 'REJECTED'

export interface AuditTimelineEntry {
  id: string
  eventType: string
  actor: string
  decision: string | null
  reason: string | null
  timestamp: string
}

export interface RecoveryDemoScenario {
  scenarioLabel: string
  transactionId: string
  externalTransactionId: string
  transactionStatus: string
  amount: number
  currency: string

  riskScore: number
  riskLevel: RiskLevel
  amountAtRisk: number
  recoveryProbability: number
  potentialRecoveryValue: number
  riskFactors: string[]
  riskReason: string

  aiRecommendedAction: RecoveryAction | null
  aiConfidence: number | null
  aiRationale: string | null

  policyDecision: PolicyDecision | null
  policyReason: string | null
  requiresHumanApproval: boolean

  finalAction: RecoveryAction | null
  executed: boolean
  executionStatus: RecoveryAttemptStatus | null
  provider: string | null
  simulated: boolean
  amountRecovered: number
  failureCode: string | null
  duplicate: boolean

  paymentConfirmationStatus: PaymentConfirmationStatus | null
  confirmedAmount: number | null
  providerPaymentId: string | null
  confirmedAt: string | null

  safetyExplanation: string
  auditTimeline: AuditTimelineEntry[]
}

export interface RecoveryDemoSummary {
  scenariosEvaluated: number
  atRiskScenarios: number
  allowedCount: number
  blockedCount: number
  escalatedCount: number
  stoppedCount: number
  executedCount: number
  gatewayCalls: number
  simulatedExecutions: number
  totalAmountAtRisk: number
  totalPotentialRecoveryValue: number
  confirmedAmountRecovered: number
  scenarios: RecoveryDemoScenario[]
}

/** "Outcome" badge shown per scenario — derived on the client from real response fields only. */
export function outcomeLabel(scenario: RecoveryDemoScenario): 'SUCCESS' | 'FAILED' | 'NOT EXECUTED' | 'PENDING CONFIRMATION' {
  if (!scenario.executed) return 'NOT EXECUTED'
  if (scenario.executionStatus === 'FAILED') return 'FAILED'
  if (scenario.executionStatus === 'SUCCESS' && scenario.paymentConfirmationStatus === 'CONFIRMED') return 'SUCCESS'
  return 'PENDING CONFIRMATION'
}
