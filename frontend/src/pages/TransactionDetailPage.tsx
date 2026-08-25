import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { Badge, riskTone } from '../components/Badge'
import { AuditTimeline } from '../components/AuditTimeline'
import type { AgentEvaluation, ExecutionResult, PolicyDecisionResult, RiskAnalysis } from '../types/recovery'
import { aiProviderLabel } from '../types/recovery'
import type { AuditEntry, RecoveryAttemptSummary, TransactionFullDetail } from '../types/transaction'

const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })
const percent = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 1 })

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-2 py-0.5">
      <dt className="text-[var(--color-text-secondary)]">{label}</dt>
      <dd className="text-right text-[var(--color-text-primary)]">{children}</dd>
    </div>
  )
}

function Section({ title, children, action }: { title: string; children: React.ReactNode; action?: React.ReactNode }) {
  return (
    <section className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">{title}</h2>
        {action}
      </div>
      <div className="mt-3">{children}</div>
    </section>
  )
}

function ActionButton({
  onClick,
  loading,
  disabled,
  disabledReason,
  variant = 'secondary',
  children,
}: {
  onClick: () => void
  loading: boolean
  disabled?: boolean
  disabledReason?: string
  variant?: 'primary' | 'secondary'
  children: React.ReactNode
}) {
  const isDisabled = disabled || loading
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={isDisabled}
      title={isDisabled && disabledReason ? disabledReason : undefined}
      className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        variant === 'primary'
          ? 'bg-[var(--color-accent)] text-[var(--color-surface-0)] hover:opacity-90'
          : 'border border-[var(--color-border)] bg-[var(--color-surface-2)] text-[var(--color-text-primary)] hover:opacity-90'
      }`}
    >
      {loading ? 'Working…' : children}
    </button>
  )
}

function InlineError({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2 rounded-lg border border-[var(--color-danger)] bg-[color-mix(in_srgb,var(--color-danger)_8%,transparent)] px-3 py-2 text-sm text-[var(--color-danger)]">
      <span>{error.message}</span>
      {error.retryAfterSeconds != null && <span className="opacity-80">(retry after {error.retryAfterSeconds}s)</span>}
      <button type="button" onClick={onRetry} className="ml-auto rounded-md border border-current px-2 py-0.5 text-xs hover:opacity-80">
        Retry
      </button>
    </div>
  )
}

function RecoveryAttemptRow({ attempt }: { attempt: RecoveryAttemptSummary }) {
  return (
    <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3 text-sm">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-medium text-[var(--color-text-primary)]">
          #{attempt.attemptNumber} — {attempt.action}
        </span>
        <Badge tone={attempt.status === 'SUCCESS' ? 'success' : attempt.status === 'FAILED' ? 'danger' : 'neutral'}>{attempt.status}</Badge>
      </div>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs sm:grid-cols-3">
        <Row label="Provider">{attempt.provider ?? '—'}</Row>
        <Row label="Simulated">{attempt.simulated ? 'Yes' : 'No'}</Row>
        <Row label="Amount">{currency.format(attempt.amount)}</Row>
        <Row label="Payment">
          <Badge tone={attempt.paymentConfirmationStatus === 'CONFIRMED' ? 'success' : attempt.paymentConfirmationStatus === 'REJECTED' ? 'danger' : 'neutral'}>
            {attempt.paymentConfirmationStatus.replace('_', ' ')}
          </Badge>
        </Row>
        <Row label="Confirmed amount">{attempt.confirmedAmount != null ? currency.format(attempt.confirmedAmount) : '—'}</Row>
        <Row label="Executed">{new Date(attempt.executedAt).toLocaleString()}</Row>
      </dl>
      {attempt.simulated && (
        <div className="mt-2 inline-flex items-center rounded-full border border-[var(--color-warning)] px-2 py-0.5 text-[10px] font-semibold tracking-wide text-[var(--color-warning)]">
          SIMULATION — NO REAL MONEY MOVED
        </div>
      )}
    </div>
  )
}

export function TransactionDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [detail, setDetail] = useState<TransactionFullDetail | null>(null)
  const [loadError, setLoadError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)

  const [risk, setRisk] = useState<RiskAnalysis | null>(null)
  const [agentEvaluation, setAgentEvaluation] = useState<AgentEvaluation | null>(null)
  const [policyRecheck, setPolicyRecheck] = useState<PolicyDecisionResult | null>(null)
  const [execution, setExecution] = useState<ExecutionResult | null>(null)
  const [audit, setAudit] = useState<AuditEntry[] | null>(null)

  const load = () => {
    if (!id) return
    setLoading(true)
    setLoadError(null)
    api
      .transactionFullDetail(id)
      .then((res) => {
        setDetail(res.data)
        setRisk(null)
        setAgentEvaluation(null)
        setPolicyRecheck(null)
        setExecution(null)
        setAudit(null)
      })
      .catch((err) => setLoadError(toApiError(err)))
      .finally(() => setLoading(false))
  }

  useEffect(load, [id])

  const analyzeAction = useAsyncAction(() => api.analyzeRisk(id!).then((r) => r.data))
  const recommendAction = useAsyncAction(() => api.getAiRecommendation(id!).then((r) => r.data))
  const recommendedAction = agentEvaluation?.aiRecommendation.action ?? null
  const policyAction = useAsyncAction(() => api.evaluatePolicy(id!, recommendedAction ?? 'RETRY_PAYMENT').then((r) => r.data))
  const executeAction = useAsyncAction(() => api.executeRecovery(id!).then((r) => r.data))
  const auditAction = useAsyncAction(() => api.auditTimeline(id!).then((r) => r.data))
  const approveAction = useAsyncAction(() => api.approveEscalation(id!).then((r) => r.data))
  const rejectAction = useAsyncAction(() => api.rejectEscalation(id!).then((r) => r.data))

  async function handleAnalyzeRisk() {
    const result = await analyzeAction.run()
    if (result) setRisk(result)
  }

  async function handleGetRecommendation() {
    const result = await recommendAction.run()
    if (result) {
      setAgentEvaluation(result)
      setPolicyRecheck(null)
    }
  }

  async function handleEvaluatePolicy() {
    const result = await policyAction.run()
    if (result) setPolicyRecheck(result)
  }

  async function handleExecute() {
    const result = await executeAction.run()
    if (result) {
      setExecution(result)
      load()
    }
  }

  async function handleRefreshAudit() {
    const result = await auditAction.run()
    if (result) setAudit(result)
  }

  async function handleApprove() {
    const result = await approveAction.run()
    if (result) {
      setExecution(result)
      load()
    }
  }

  async function handleReject() {
    const result = await rejectAction.run()
    if (result) load()
  }

  if (!id) return null;

  if (loading && !detail) {
    return (
      <div className="mx-auto max-w-4xl px-6 py-10 text-sm text-[var(--color-text-secondary)]">
        Loading transaction…
      </div>
    )
  }

  if (loadError) {
    return (
      <div className="mx-auto max-w-4xl px-6 py-10">
        <Link to="/transactions" className="text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]">
          ← Transactions
        </Link>
        <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-[var(--color-danger)] bg-[var(--color-surface-1)] px-4 py-3 text-sm text-[var(--color-danger)]">
          <span>{loadError.message}</span>
          <button onClick={load} className="ml-auto rounded-md border border-current px-3 py-1 text-xs hover:opacity-80">
            Retry
          </button>
        </div>
      </div>
    )
  }

  if (!detail) return null

  const t = detail.transaction
  const policyDecision = policyRecheck?.decision ?? agentEvaluation?.policyDecision.decision ?? null
  const policyReason = policyRecheck?.reason ?? agentEvaluation?.policyDecision.reason ?? null
  const alreadyExecuted = detail.recoveryAttempts.some((a) => a.status === 'SUCCESS')
  const canExecute = policyDecision === 'ALLOW' && !alreadyExecuted && !execution?.executed
  const auditEntries = audit ?? detail.auditTimeline

  return (
    <div className="mx-auto max-w-4xl space-y-5 px-6 py-10">
      <div>
        <Link to="/transactions" className="text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]">
          ← Transactions
        </Link>
        <div className="mt-1 flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-2xl font-semibold text-[var(--color-text-primary)]">{t.externalTransactionId}</h1>
          <ActionButton onClick={load} loading={loading}>
            Refresh transaction
          </ActionButton>
        </div>
      </div>

      <Section title="Transaction">
        <dl className="space-y-1 text-sm">
          <Row label="ID">
            <span className="font-mono text-xs">{t.id}</span>
          </Row>
          <Row label="Amount">{currency.format(t.amount)} {t.currency}</Row>
          <Row label="Status">{t.status}</Row>
          <Row label="Payment method">{t.paymentMethod ?? '—'}</Row>
          <Row label="Failure category">{t.failureCode ?? '—'}</Row>
          <Row label="Attempt count">{t.attemptCount}</Row>
          <Row label="Created">{new Date(t.createdAt).toLocaleString()}</Row>
        </dl>
      </Section>

      <Section title="Customer">
        <dl className="space-y-1 text-sm">
          <Row label="Name">{t.customerName}</Row>
          <Row label="Email">{t.customerEmail}</Row>
          <Row label="Successful payments">{detail.customerSuccessfulPaymentCount}</Row>
          <Row label="Failed payments">{detail.customerFailedPaymentCount}</Row>
          <Row label="Historical value">{currency.format(detail.customerTotalHistoricalValue)}</Row>
          <Row label="Recovery contact">
            <Badge tone={detail.customerRecoveryContactAllowed ? 'success' : 'danger'}>
              {detail.customerRecoveryContactAllowed ? 'Allowed' : 'Opted out'}
            </Badge>
          </Row>
        </dl>
        {!detail.customerRecoveryContactAllowed && (
          <p className="mt-2 text-sm text-[var(--color-danger)]">
            Recovery stopped — customer has opted out. The backend blocks every autonomous recovery action
            (retry, payment link, reminder) for this customer regardless of AI recommendation or policy check.
          </p>
        )}
      </Section>

      <Section
        title="Revenue risk"
        action={
          <ActionButton onClick={handleAnalyzeRisk} loading={analyzeAction.loading}>
            {risk || detail.risk ? 'Re-analyze risk' : 'Analyze risk'}
          </ActionButton>
        }
      >
        {analyzeAction.error && <InlineError error={analyzeAction.error} onRetry={handleAnalyzeRisk} />}
        {risk || detail.risk ? (
          <>
            <dl className="space-y-1 text-sm">
              <Row label="Risk score">{(risk ?? detail.risk)!.riskScore.toFixed(2)} / 100</Row>
              <Row label="Risk level">
                <Badge tone={riskTone((risk ?? detail.risk)!.riskLevel)}>{(risk ?? detail.risk)!.riskLevel}</Badge>
              </Row>
              <Row label="Amount at risk">{currency.format((risk ?? detail.risk)!.amountAtRisk)}</Row>
              <Row label="Recovery probability">{percent.format((risk ?? detail.risk)!.recoveryProbability)}</Row>
              <Row label="Potential recovery value">{currency.format((risk ?? detail.risk)!.potentialRecoveryValue)}</Row>
            </dl>
            <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{(risk ?? detail.risk)!.reason}</p>
          </>
        ) : (
          <p className="text-sm text-[var(--color-text-secondary)]">Not analyzed yet.</p>
        )}
      </Section>

      <Section
        title="AI recommendation"
        action={
          <ActionButton onClick={handleGetRecommendation} loading={recommendAction.loading}>
            {agentEvaluation ? 'Re-evaluate' : 'Get AI recommendation'}
          </ActionButton>
        }
      >
        {recommendAction.error && <InlineError error={recommendAction.error} onRetry={handleGetRecommendation} />}
        {agentEvaluation ? (
          <dl className="space-y-1 text-sm">
            <Row label="Recommended action">{agentEvaluation.aiRecommendation.action}</Row>
            <Row label="Confidence">{percent.format(agentEvaluation.aiRecommendation.confidence)}</Row>
            <Row label="AI provider">{aiProviderLabel(agentEvaluation.aiRecommendation.provider)}</Row>
          </dl>
        ) : (
          <p className="text-sm text-[var(--color-text-secondary)]">No recommendation requested yet.</p>
        )}
      </Section>

      <Section
        title="Safety policy"
        action={
          <ActionButton onClick={handleEvaluatePolicy} loading={policyAction.loading} disabled={!recommendedAction} disabledReason="Get an AI recommendation first.">
            Evaluate policy
          </ActionButton>
        }
      >
        {policyAction.error && <InlineError error={policyAction.error} onRetry={handleEvaluatePolicy} />}
        {policyDecision ? (
          <div className="rounded-lg border border-[var(--color-border)] p-3 text-sm">
            <div className="font-semibold text-[var(--color-text-primary)]">{policyDecision}</div>
            <div className="mt-1 text-[var(--color-text-secondary)]">{policyReason}</div>
          </div>
        ) : (
          <p className="text-sm text-[var(--color-text-secondary)]">No policy decision yet — get an AI recommendation first.</p>
        )}
      </Section>

      {t.status === 'ESCALATED' && (
        <Section title="Escalation review">
          <p className="text-sm text-[var(--color-text-secondary)]">
            This transaction is escalated and will not execute automatically. Approving does not itself authorize
            execution — it re-runs the full AI and policy pipeline fresh, and only executes if that fresh check
            still says ALLOW. Rejecting leaves it escalated and only records that a human declined.
          </p>
          <div className="mt-3 flex gap-2">
            <ActionButton onClick={handleApprove} loading={approveAction.loading} variant="primary">
              Approve → re-evaluate
            </ActionButton>
            <ActionButton onClick={handleReject} loading={rejectAction.loading}>
              Reject
            </ActionButton>
          </div>
          {approveAction.error && <InlineError error={approveAction.error} onRetry={handleApprove} />}
          {rejectAction.error && <InlineError error={rejectAction.error} onRetry={handleReject} />}
        </Section>
      )}

      <Section
        title="Recovery"
        action={
          <ActionButton
            onClick={handleExecute}
            loading={executeAction.loading}
            disabled={!canExecute}
            disabledReason={
              alreadyExecuted
                ? 'Already executed for this transaction.'
                : policyDecision !== 'ALLOW'
                  ? `Policy decision is ${policyDecision ?? 'unknown'} — execution is not authorized.`
                  : undefined
            }
            variant="primary"
          >
            Execute recovery
          </ActionButton>
        }
      >
        {executeAction.error && <InlineError error={executeAction.error} onRetry={handleExecute} />}
        {execution && (
          <div className="mb-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3 text-sm text-[var(--color-text-secondary)]">
            {execution.executed
              ? execution.paymentConfirmationStatus === 'CONFIRMED'
                ? `✓ Confirmed Revenue Recovered — ${currency.format(execution.amountRecovered)}`
                : 'Recovery action executed. Payment not yet confirmed — amountRecovered stays ₹0.00 until a verified webhook confirms it.'
              : (execution.executionNote ?? 'Not executed.')}
          </div>
        )}
        <div className="space-y-2">
          {detail.recoveryAttempts.length === 0 ? (
            <p className="text-sm text-[var(--color-text-secondary)]">No recovery attempts yet.</p>
          ) : (
            detail.recoveryAttempts.map((a) => <RecoveryAttemptRow key={a.id} attempt={a} />)
          )}
        </div>
      </Section>

      <Section
        title="Audit timeline"
        action={
          <ActionButton onClick={handleRefreshAudit} loading={auditAction.loading}>
            Refresh audit
          </ActionButton>
        }
      >
        {auditAction.error && <InlineError error={auditAction.error} onRetry={handleRefreshAudit} />}
        <AuditTimeline entries={auditEntries} />
      </Section>
    </div>
  )
}
