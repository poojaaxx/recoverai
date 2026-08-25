import type { PaymentConfirmationStatus, RecoveryAction, RecoveryAttemptStatus, RiskLevel } from './demo'

export type TransactionStatus = 'SUCCESS' | 'FAILED' | 'PENDING' | 'ABANDONED' | 'RECOVERED' | 'ESCALATED' | 'STOPPED'
export type PaymentMethod = 'CARD' | 'UPI' | 'NETBANKING' | 'WALLET' | 'EMI'
export type TransactionSort =
  | 'NEWEST'
  | 'OLDEST'
  | 'AMOUNT_DESC'
  | 'RISK_SCORE_DESC'
  | 'AMOUNT_AT_RISK_DESC'
  | 'RECOVERY_PROBABILITY_DESC'

/** One row of the general-purpose transaction dashboard — real data only; risk/recovery fields are null when not yet analyzed/attempted. */
export interface TransactionListItem {
  id: string
  externalTransactionId: string
  amount: number
  currency: string
  status: TransactionStatus
  paymentMethod: PaymentMethod | null
  failureCode: string | null
  attemptCount: number
  createdAt: string

  riskScore: number | null
  riskLevel: RiskLevel | null
  recoveryProbability: number | null
  amountAtRisk: number | null

  latestRecoveryAction: RecoveryAction | null
  latestRecoveryStatus: RecoveryAttemptStatus | null
  latestRecoveryAt: string | null
}

export interface TransactionListPage {
  content: TransactionListItem[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface TransactionListFilters {
  status?: TransactionStatus
  riskLevel?: RiskLevel
  failureCategory?: string
  paymentMethod?: PaymentMethod
  minAmount?: number
  maxAmount?: number
  search?: string
  atRiskOnly?: boolean
  recoveredOnly?: boolean
  recoveryAttemptStatus?: RecoveryAttemptStatus
  sort?: TransactionSort
  page?: number
  size?: number
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
  status: TransactionStatus
  paymentMethod: PaymentMethod | null
  failureCode: string | null
  failureReason: string | null
  attemptCount: number
  createdAt: string
  updatedAt: string
}

export interface RevenueRiskDetail {
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

export interface RecoveryAttemptSummary {
  id: string
  action: RecoveryAction
  attemptNumber: number
  status: RecoveryAttemptStatus
  provider: string | null
  providerReference: string | null
  simulated: boolean
  amount: number
  amountRecovered: number | null
  paymentConfirmationStatus: PaymentConfirmationStatus
  confirmedAmount: number | null
  providerPaymentId: string | null
  confirmedAt: string | null
  executedAt: string
}

export interface AuditEntry {
  id: string
  eventType: string
  actor: string
  decision: string | null
  reason: string | null
  timestamp: string
}

/** GET /api/transactions/{id}/detail — one bundled fetch of everything the detail page shows. Reused, not fabricated. */
export interface TransactionFullDetail {
  transaction: TransactionDetail
  customerSuccessfulPaymentCount: number
  customerFailedPaymentCount: number
  customerTotalHistoricalValue: number
  /** Phase 14 — false means the customer has opted out of recovery contact; the backend blocks every autonomous recovery action for them regardless of what the frontend shows. */
  customerRecoveryContactAllowed: boolean
  risk: RevenueRiskDetail | null
  recoveryAttempts: RecoveryAttemptSummary[]
  auditTimeline: AuditEntry[]
}
