import axios from 'axios'
import { clearSession, getSession, type UserRole } from './auth'
import type { RecoveryAction, RecoveryDemoScenario, RecoveryDemoSummary } from '../types/demo'
import type {
  AgentEvaluation,
  AuditEntry,
  BatchAgentEvaluationResult,
  BatchRiskAnalysisResult,
  ExecutionResult,
  HealthStatus,
  PolicyDecisionResult,
  RecoveryMetrics,
  RiskAnalysis,
  RiskMetrics,
  TransactionDetail,
} from '../types/recovery'
import type { TransactionFullDetail, TransactionListFilters, TransactionListPage } from '../types/transaction'
import type { ObservabilityMetrics } from '../types/observability'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/**
 * Generous timeout - the deployed backend (Render free tier) can take up
 * to a couple of minutes to respond to the first request after being idle
 * (a cold start), not just a slow network. See `ApiError.isLikelyColdStart`.
 */
const REQUEST_TIMEOUT_MS = 120_000

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: REQUEST_TIMEOUT_MS,
})

/** Attaches the logged-in user's bearer token, when present, to every request. The backend remains the sole authority on whether the token/role is actually allowed to do anything. */
apiClient.interceptors.request.use((config) => {
  const session = getSession()
  if (session) {
    config.headers.Authorization = `Bearer ${session.token}`
  }
  return config
})

/** A 401 means the token is missing/expired/invalid - clear it and send the user back to the login page rather than showing a confusing error inline. */
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (axios.isAxiosError(error) && error.response?.status === 401 && !error.config?.url?.endsWith('/api/auth/login')) {
      clearSession()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)

export interface ApiError {
  status: number | null
  message: string
  retryAfterSeconds: number | null
  isLikelyColdStart: boolean
}

/** Normalizes any error from an `apiClient` call into a safe, user-facing shape — never a raw stack trace or Axios internals. */
export function toApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status ?? null
    const backendMessage = (error.response?.data as { error?: string } | undefined)?.error
    const retryAfterHeader = error.response?.headers?.['retry-after']
    const retryAfterSeconds = retryAfterHeader ? Number(retryAfterHeader) : null

    if (!error.response) {
      // No response reached us at all - either a cold-starting backend, or a real network/CORS problem.
      const timedOut = error.code === 'ECONNABORTED'
      return {
        status: null,
        message: timedOut
          ? 'The backend took too long to respond — it may be waking up from being idle. Please try again in a moment.'
          : 'Could not reach the RecoverAI backend. It may be waking up from being idle — please try again in a moment.',
        retryAfterSeconds: null,
        isLikelyColdStart: true,
      }
    }

    if (backendMessage) {
      return { status, message: backendMessage, retryAfterSeconds, isLikelyColdStart: false }
    }

    switch (status) {
      case 403:
        return {
          status,
          message: "You don't have permission to perform this action. This requires MERCHANT_ADMIN access.",
          retryAfterSeconds,
          isLikelyColdStart: false,
        }
      case 404:
        return { status, message: 'Not found.', retryAfterSeconds, isLikelyColdStart: false }
      case 429:
        return {
          status,
          message: 'Too many requests — please slow down and try again shortly.',
          retryAfterSeconds,
          isLikelyColdStart: false,
        }
      case 503:
        return {
          status,
          message: 'The backend is temporarily unavailable. Please try again shortly.',
          retryAfterSeconds,
          isLikelyColdStart: false,
        }
      default:
        return { status, message: 'Something went wrong. Please try again.', retryAfterSeconds, isLikelyColdStart: false }
    }
  }
  return { status: null, message: 'Something went wrong. Please try again.', retryAfterSeconds: null, isLikelyColdStart: false }
}

/**
 * Typed calls to the real RecoverAI backend, one per endpoint this
 * frontend uses. Every function is a thin wrapper around `apiClient` -
 * no client-side computation of risk/AI/policy results, no fabricated
 * data. The backend remains authoritative for every decision.
 */
export interface LoginResponse {
  token: string
  tokenType: string
  role: UserRole
  expiresInSeconds: number
}

export const api = {
  health: () => apiClient.get<HealthStatus>('/api/health'),

  login: (username: string, password: string) =>
    apiClient.post<LoginResponse>('/api/auth/login', { username, password }),

  demoSummary: () => apiClient.get<RecoveryDemoSummary>('/api/demo/recovery'),
  demoScenario: (externalTransactionId: string) =>
    apiClient.get<RecoveryDemoScenario>(`/api/demo/recovery/${externalTransactionId}`),

  transaction: (transactionId: string) => apiClient.get<TransactionDetail>(`/api/transactions/${transactionId}`),

  /** General-purpose transaction dashboard (any transaction, not just the 5 curated demo scenarios) - server-side filtering/search/sort/pagination. */
  transactions: (filters: TransactionListFilters) => apiClient.get<TransactionListPage>('/api/transactions', { params: filters }),
  transactionFullDetail: (transactionId: string) =>
    apiClient.get<TransactionFullDetail>(`/api/transactions/${transactionId}/detail`),

  analyzeRisk: (transactionId: string) => apiClient.post<RiskAnalysis>(`/api/revenue-risk/analyze/${transactionId}`),
  getRisk: (transactionId: string) => apiClient.get<RiskAnalysis>(`/api/revenue-risk/${transactionId}`),
  riskMetrics: () => apiClient.get<RiskMetrics>('/api/revenue-risk/metrics'),
  analyzeAllRisk: () => apiClient.post<BatchRiskAnalysisResult>('/api/revenue-risk/analyze-all'),
  evaluateAllWithAi: () => apiClient.post<BatchAgentEvaluationResult>('/api/recovery-agent/evaluate-all'),

  getAiRecommendation: (transactionId: string) =>
    apiClient.post<AgentEvaluation>(`/api/recovery-agent/evaluate/${transactionId}`),

  evaluatePolicy: (transactionId: string, action: RecoveryAction) =>
    apiClient.post<PolicyDecisionResult>(`/api/recovery-policy/evaluate/${transactionId}`, { action }),

  executeRecovery: (transactionId: string) => apiClient.post<ExecutionResult>(`/api/recovery/${transactionId}/execute`),
  recoveryMetrics: () => apiClient.get<RecoveryMetrics>('/api/recovery/metrics'),
  observabilityMetrics: () => apiClient.get<ObservabilityMetrics>('/api/observability/metrics'),

  auditTimeline: (transactionId: string) => apiClient.get<AuditEntry[]>(`/api/audit/${transactionId}`),
}
