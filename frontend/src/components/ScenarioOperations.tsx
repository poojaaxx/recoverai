import { useEffect, useState, type ReactNode } from 'react'
import { api, type ApiError } from '../lib/api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import type { RecoveryDemoScenario } from '../types/demo'
import type {
  AgentEvaluation,
  AuditEntry,
  ExecutionResult,
  PolicyDecisionResult,
  RiskAnalysis,
  TestPaymentConfirmation,
  TransactionDetail,
} from '../types/recovery'
import { aiProviderLabel } from '../types/recovery'
import { Badge, riskTone } from './Badge'
import { AuditTimeline } from './AuditTimeline'

const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })
const percent = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 0 })

type GuidedStage =
  | 'idle'
  | 'analyzing-risk'
  | 'getting-recommendation'
  | 'ready-for-execution'
  | 'blocked'
  | 'executing'
  | 'completed'

/** Per-selected-scenario live results — resets whenever the selected transaction changes. Never fabricated: every field here is either `null` (not fetched yet) or a real backend response. */
interface OperationalState {
  transactionId: string
  risk: RiskAnalysis | null
  agentEvaluation: AgentEvaluation | null
  policyRecheck: PolicyDecisionResult | null
  execution: ExecutionResult | null
  audit: AuditEntry[] | null
  transaction: TransactionDetail | null
  guidedStage: GuidedStage
  guidedMessage: string | null
  testConfirmation: TestPaymentConfirmation | null
}

function freshState(transactionId: string): OperationalState {
  return {
    transactionId,
    risk: null,
    agentEvaluation: null,
    policyRecheck: null,
    execution: null,
    audit: null,
    transaction: null,
    guidedStage: 'idle',
    guidedMessage: null,
    testConfirmation: null,
  }
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-2">
      <dt className="text-[var(--color-text-secondary)]">{label}</dt>
      <dd className="text-right text-[var(--color-text-primary)]">{children}</dd>
    </div>
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
  variant?: 'primary' | 'secondary' | 'danger'
  children: ReactNode
}) {
  const isDisabled = disabled || loading
  const base = 'rounded-lg px-3.5 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50'
  const variantClasses =
    variant === 'primary'
      ? 'bg-[var(--color-accent)] text-[var(--color-surface-0)] hover:opacity-90'
      : variant === 'danger'
        ? 'border border-[var(--color-danger)] text-[var(--color-danger)] hover:bg-[color-mix(in_srgb,var(--color-danger)_10%,transparent)]'
        : 'border border-[var(--color-border)] bg-[var(--color-surface-1)] text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)]'

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={isDisabled}
      title={isDisabled && disabledReason ? disabledReason : undefined}
      className={`${base} ${variantClasses}`}
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

/** Explains BLOCK / ESCALATE / STOP / ALLOW plainly — never lets the frontend imply an action is authorized when it isn't. */
function PolicyStatusBanner({ decision, reason }: { decision: PolicyDecisionResult['decision']; reason: string }) {
  const copy: Record<PolicyDecisionResult['decision'], { title: string; tone: 'success' | 'warning' | 'danger' }> = {
    ALLOW: { title: 'Authorized for execution', tone: 'success' },
    ESCALATE: { title: 'Human approval required', tone: 'warning' },
    BLOCK: { title: 'Execution blocked', tone: 'danger' },
    STOP: { title: 'Recovery stopped', tone: 'danger' },
  }
  const { title, tone } = copy[decision]
  const toneClasses =
    tone === 'success'
      ? 'border-[var(--color-success)] text-[var(--color-success)]'
      : tone === 'warning'
        ? 'border-[var(--color-warning)] text-[var(--color-warning)]'
        : 'border-[var(--color-danger)] text-[var(--color-danger)]'
  return (
    <div className={`mt-2 rounded-lg border px-3 py-2 text-sm ${toneClasses}`}>
      <div className="font-semibold">{title}</div>
      <div className="mt-0.5 text-[var(--color-text-secondary)]">{reason}</div>
    </div>
  )
}

function GuidedProgress({ stage, message }: { stage: GuidedStage; message: string | null }) {
  if (stage === 'idle') return null
  const steps: { key: GuidedStage; label: string }[] = [
    { key: 'analyzing-risk', label: 'Analyzing risk' },
    { key: 'getting-recommendation', label: 'Getting AI recommendation' },
    { key: 'ready-for-execution', label: 'Ready for execution' },
    { key: 'executing', label: 'Executing' },
    { key: 'completed', label: 'Completed' },
  ]
  const order: GuidedStage[] = ['analyzing-risk', 'getting-recommendation', 'ready-for-execution', 'executing', 'completed']
  const currentIndex = stage === 'blocked' ? 2 : order.indexOf(stage)

  return (
    <div className="mt-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3">
      <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs">
        {steps.map((step, i) => {
          const isBlockedHere = stage === 'blocked' && step.key === 'ready-for-execution'
          const reached = i <= currentIndex
          return (
            <span key={step.key} className="flex items-center gap-1.5">
              <span
                className={
                  isBlockedHere
                    ? 'text-[var(--color-danger)] font-medium'
                    : reached
                      ? 'text-[var(--color-accent)] font-medium'
                      : 'text-[var(--color-text-secondary)]'
                }
              >
                {isBlockedHere ? 'Stopped' : step.label}
              </span>
              {i < steps.length - 1 && <span className="text-[var(--color-text-secondary)]">→</span>}
            </span>
          )
        })}
      </div>
      {message && <div className="mt-2 text-sm text-[var(--color-text-secondary)]">{message}</div>}
    </div>
  )
}

export function ScenarioOperations({
  scenario,
  onDashboardRefreshNeeded,
}: {
  scenario: RecoveryDemoScenario
  onDashboardRefreshNeeded: () => void
}) {
  const [op, setOp] = useState<OperationalState>(() => freshState(scenario.transactionId))

  useEffect(() => {
    setOp(freshState(scenario.transactionId))
  }, [scenario.transactionId])

  const risk = op.risk ?? {
    riskScore: scenario.riskScore,
    riskLevel: scenario.riskLevel,
    amountAtRisk: scenario.amountAtRisk,
    recoveryProbability: scenario.recoveryProbability,
    potentialRecoveryValue: scenario.potentialRecoveryValue,
    factors: scenario.riskFactors,
    reason: scenario.riskReason,
  }

  const recommendedAction = op.agentEvaluation?.aiRecommendation.action ?? scenario.aiRecommendedAction
  const aiConfidence = op.agentEvaluation?.aiRecommendation.confidence ?? scenario.aiConfidence
  const aiRationale = op.agentEvaluation?.aiRecommendation.rationale ?? scenario.aiRationale
  const aiProvider = op.agentEvaluation?.aiRecommendation.provider ?? null
  const aiModel = op.agentEvaluation?.aiRecommendation.model ?? null
  const expectedRecoveryValue = op.agentEvaluation?.expectedRecoveryValue ?? null

  const policyDecision = op.policyRecheck?.decision ?? op.agentEvaluation?.policyDecision.decision ?? scenario.policyDecision
  const policyReason = op.policyRecheck?.reason ?? op.agentEvaluation?.policyDecision.reason ?? scenario.policyReason
  const policyChecks = op.policyRecheck?.policyChecks ?? op.agentEvaluation?.policyDecision.policyChecks ?? null
  const requiresHumanApproval = op.policyRecheck?.requiresHumanApproval ?? scenario.requiresHumanApproval
  const finalAction = op.agentEvaluation?.finalAction ?? scenario.finalAction
  const aiDiffersFromPolicy = recommendedAction != null && finalAction != null && recommendedAction !== finalAction

  const executed = op.execution?.executed ?? scenario.executed
  const provider = op.execution?.provider ?? scenario.provider
  const simulated = op.execution?.simulated ?? scenario.simulated
  const amountRecovered = op.execution?.amountRecovered ?? scenario.amountRecovered
  const failureCode = op.execution?.failureCode ?? scenario.failureCode
  const duplicate = op.execution?.duplicate ?? scenario.duplicate
  const executionStatus = op.execution?.executionStatus ?? scenario.executionStatus
  const paymentConfirmationStatus = op.execution?.paymentConfirmationStatus ?? scenario.paymentConfirmationStatus ?? 'NOT_CONFIRMED'
  const confirmedAmount = op.execution?.confirmedAmount ?? scenario.confirmedAmount
  const providerPaymentId = op.execution?.providerPaymentId ?? scenario.providerPaymentId
  // Only ever present on the direct response of the execution call that just created it - never
  // persisted or re-fetched, so a stale/re-rendered card can't show a link from an older attempt.
  const paymentLinkUrl = op.execution?.paymentLinkUrl ?? null

  const auditEntries = op.audit ?? scenario.auditTimeline

  const canExecute = policyDecision === 'ALLOW' && !executed
  const executeDisabledReason =
    policyDecision !== 'ALLOW'
      ? `Policy decision is ${policyDecision ?? 'unknown'} — execution is not authorized.`
      : executed
        ? 'Already executed for this transaction.'
        : undefined

  const analyzeAction = useAsyncAction(() => api.analyzeRisk(scenario.transactionId).then((r) => r.data))
  const recommendAction = useAsyncAction(() => api.getAiRecommendation(scenario.transactionId).then((r) => r.data))
  const policyAction = useAsyncAction(() =>
    api.evaluatePolicy(scenario.transactionId, recommendedAction ?? 'RETRY_PAYMENT').then((r) => r.data),
  )
  const executeAction = useAsyncAction(() => api.executeRecovery(scenario.transactionId).then((r) => r.data))
  const auditAction = useAsyncAction(() => api.auditTimeline(scenario.transactionId).then((r) => r.data))
  const confirmTestPaymentAction = useAsyncAction(() =>
    api.confirmTestPayment(scenario.transactionId).then((r) => r.data),
  )
  const transactionAction = useAsyncAction(() => api.transaction(scenario.transactionId).then((r) => r.data))

  async function handleAnalyzeRisk() {
    const result = await analyzeAction.run()
    if (result) setOp((prev) => ({ ...prev, risk: result }))
  }

  async function handleGetRecommendation() {
    const result = await recommendAction.run()
    if (result) setOp((prev) => ({ ...prev, agentEvaluation: result, policyRecheck: null }))
  }

  async function handleEvaluatePolicy() {
    const result = await policyAction.run()
    if (result) setOp((prev) => ({ ...prev, policyRecheck: result }))
  }

  async function handleRefreshAudit() {
    const result = await auditAction.run()
    if (result) setOp((prev) => ({ ...prev, audit: result }))
  }

  async function handleRefreshTransaction() {
    const result = await transactionAction.run()
    if (result) setOp((prev) => ({ ...prev, transaction: result }))
  }

  async function handleConfirmTestPayment() {
    const result = await confirmTestPaymentAction.run()
    if (!result) return
    setOp((prev) => ({
      ...prev,
      testConfirmation: result,
      // Synchronize the real confirmation fields into `execution` - it previously only ever held
      // the pre-confirmation snapshot from handleExecute(), so paymentConfirmationStatus/
      // confirmedAmount stayed stuck at NOT_CONFIRMED/null here even after a real confirmation
      // succeeded. Every value below comes straight from the confirm-test-payment API response.
      execution:
        prev.execution && result.outcome === 'CONFIRMED'
          ? {
              ...prev.execution,
              paymentConfirmationStatus: 'CONFIRMED',
              confirmedAmount: result.confirmedAmount,
              amountRecovered: result.confirmedAmount ?? prev.execution.amountRecovered,
            }
          : prev.execution,
    }))
    await Promise.all([handleRefreshTransaction(), handleRefreshAudit()])
    onDashboardRefreshNeeded()
  }

  async function handleExecute() {
    setOp((prev) => ({ ...prev, guidedStage: 'executing', guidedMessage: 'Calling the recovery execution endpoint…' }))
    const result = await executeAction.run()
    if (!result) {
      setOp((prev) => ({ ...prev, guidedStage: 'idle', guidedMessage: null }))
      return
    }
    setOp((prev) => ({ ...prev, execution: result, guidedStage: 'completed', guidedMessage: null }))
    await Promise.all([handleRefreshTransaction(), handleRefreshAudit()])
    onDashboardRefreshNeeded()
  }

  async function handleRunDemo() {
    setOp((prev) => ({ ...prev, guidedStage: 'analyzing-risk', guidedMessage: null }))
    const riskResult = await analyzeAction.run()
    if (!riskResult) {
      setOp((prev) => ({ ...prev, guidedStage: 'idle' }))
      return
    }

    setOp((prev) => ({ ...prev, risk: riskResult, guidedStage: 'getting-recommendation' }))
    const evalResult = await recommendAction.run()
    if (!evalResult) {
      setOp((prev) => ({ ...prev, guidedStage: 'idle' }))
      return
    }

    const decision = evalResult.policyDecision.decision
    if (decision === 'ALLOW') {
      setOp((prev) => ({
        ...prev,
        agentEvaluation: evalResult,
        policyRecheck: null,
        guidedStage: 'ready-for-execution',
        guidedMessage: 'Policy authorized this action. Click "Execute Recovery" to run it through the real payment gateway.',
      }))
    } else {
      setOp((prev) => ({
        ...prev,
        agentEvaluation: evalResult,
        policyRecheck: null,
        guidedStage: 'blocked',
        guidedMessage: evalResult.policyDecision.reason,
      }))
    }
  }

  const guidedRunning = analyzeAction.loading || recommendAction.loading || executeAction.loading

  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-lg font-semibold text-[var(--color-text-primary)]">{scenario.externalTransactionId}</h3>
          <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
            {op.transaction?.status ?? scenario.transactionStatus} · {currency.format(scenario.amount)}
          </p>
        </div>
        <ActionButton onClick={handleRunDemo} loading={guidedRunning && op.guidedStage !== 'idle'} variant="primary">
          ▶ Run demo
        </ActionButton>
      </div>

      <GuidedProgress stage={op.guidedStage} message={op.guidedMessage} />

      {/* -------------------------------------------------- risk */}
      <section className="mt-5">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Risk analysis</h4>
          <ActionButton onClick={handleAnalyzeRisk} loading={analyzeAction.loading}>
            {op.risk ? 'Re-analyze risk' : 'Analyze risk'}
          </ActionButton>
        </div>
        {analyzeAction.error && <InlineError error={analyzeAction.error} onRetry={handleAnalyzeRisk} />}
        <dl className="mt-2 space-y-1 text-sm">
          <Row label="Risk score">{risk.riskScore.toFixed(2)} / 100</Row>
          <Row label="Risk level">
            <Badge tone={riskTone(risk.riskLevel)}>{risk.riskLevel}</Badge>
          </Row>
          <Row label="Amount at risk">{currency.format(risk.amountAtRisk)}</Row>
          <Row label="Recovery probability">{percent.format(risk.recoveryProbability)}</Row>
          <Row label="Potential recovery value">{currency.format(risk.potentialRecoveryValue)}</Row>
        </dl>
        <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{risk.reason}</p>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {risk.factors.map((f) => (
            <Badge key={f} tone="neutral">
              {f}
            </Badge>
          ))}
        </div>
        {op.risk && <p className="mt-2 text-xs text-[var(--color-text-secondary)]">✓ Live from the Revenue Risk Engine.</p>}
      </section>

      {/* -------------------------------------------------- AI recommendation */}
      <section className="mt-5 border-t border-[var(--color-border)] pt-5">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">AI recommendation</h4>
          <ActionButton onClick={handleGetRecommendation} loading={recommendAction.loading}>
            {op.agentEvaluation ? 'Re-evaluate' : 'Get AI recommendation'}
          </ActionButton>
        </div>
        {recommendAction.error && <InlineError error={recommendAction.error} onRetry={handleGetRecommendation} />}
        <dl className="mt-2 space-y-1 text-sm">
          <Row label="Recommended action">{recommendedAction ?? '—'}</Row>
          <Row label="Confidence">{aiConfidence != null ? percent.format(aiConfidence) : '—'}</Row>
          {aiProvider && (
            <Row label="AI provider">{aiProviderLabel(aiProvider, aiModel)}</Row>
          )}
          {expectedRecoveryValue != null && <Row label="Expected recovery value">{currency.format(expectedRecoveryValue)}</Row>}
        </dl>
        <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{aiRationale}</p>
        <p className="mt-2 text-xs font-medium text-[var(--color-text-secondary)]">
          AI <span className="text-[var(--color-accent)]">recommends</span> — it does not authorize anything by itself.
        </p>
      </section>

      {/* -------------------------------------------------- policy */}
      <section className="mt-5 border-t border-[var(--color-border)] pt-5">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Safety policy</h4>
          <ActionButton
            onClick={handleEvaluatePolicy}
            loading={policyAction.loading}
            disabled={!recommendedAction}
            disabledReason="Get an AI recommendation first — the policy check re-verifies that specific action."
          >
            Evaluate policy
          </ActionButton>
        </div>
        {policyAction.error && <InlineError error={policyAction.error} onRetry={handleEvaluatePolicy} />}
        {aiDiffersFromPolicy && (
          <div className="mt-2 rounded-md border border-[var(--color-warning)] bg-[color-mix(in_srgb,var(--color-warning)_10%,transparent)] px-3 py-2 text-sm text-[var(--color-warning)]">
            AI recommended <strong>{recommendedAction}</strong>, policy authorized <strong>{finalAction}</strong> — the AI's
            recommendation does not decide what runs.
          </div>
        )}
        {policyDecision ? (
          <PolicyStatusBanner decision={policyDecision} reason={policyReason ?? 'No reason returned.'} />
        ) : (
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">No policy decision yet — get an AI recommendation first.</p>
        )}
        <dl className="mt-2 space-y-1 text-sm">
          <Row label="Requires human approval">{requiresHumanApproval ? 'Yes' : 'No'}</Row>
        </dl>
        {policyChecks && policyChecks.length > 0 && (
          <ul className="mt-2 space-y-1 text-xs">
            {policyChecks.map((check) => (
              <li key={check.name} className="flex items-center gap-2">
                <Badge tone={check.passed ? 'success' : 'danger'}>{check.passed ? 'PASS' : 'FAIL'}</Badge>
                <span className="font-mono text-[var(--color-text-secondary)]">{check.name}</span>
                <span className="text-[var(--color-text-secondary)]">— {check.reason}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* -------------------------------------------------- execution */}
      <section className="mt-5 border-t border-[var(--color-border)] pt-5">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Execution</h4>
          <ActionButton
            onClick={handleExecute}
            loading={executeAction.loading}
            disabled={!canExecute}
            disabledReason={executeDisabledReason}
            variant="primary"
          >
            Execute recovery
          </ActionButton>
        </div>
        {executeAction.error && <InlineError error={executeAction.error} onRetry={handleExecute} />}

        {simulated && executed && (
          <div className="mt-2 inline-flex items-center gap-1.5 rounded-full border border-[var(--color-warning)] px-2.5 py-0.5 text-xs font-semibold tracking-wide text-[var(--color-warning)]">
            SIMULATION — NO REAL MONEY MOVED
          </div>
        )}

        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3">
            <div className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Execution</div>
            <dl className="mt-1.5 space-y-1 text-sm">
              <Row label="Status">{executionStatus ?? (executed ? 'SUCCESS' : 'Not executed')}</Row>
              <Row label="Provider">{provider ?? '—'}</Row>
              <Row label="Failure code">{failureCode ?? '—'}</Row>
              <Row label="Duplicate/replay">{duplicate ? 'Yes' : 'No'}</Row>
            </dl>
          </div>
          <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3">
            <div className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Payment</div>
            <dl className="mt-1.5 space-y-1 text-sm">
              <Row label="Confirmation">
                <Badge
                  tone={
                    paymentConfirmationStatus === 'CONFIRMED'
                      ? 'success'
                      : paymentConfirmationStatus === 'REJECTED'
                        ? 'danger'
                        : 'neutral'
                  }
                >
                  {paymentConfirmationStatus.replace('_', ' ')}
                </Badge>
              </Row>
              <Row label="Confirmed amount">{confirmedAmount != null ? currency.format(confirmedAmount) : '—'}</Row>
              <Row label="Provider payment id">{providerPaymentId ?? '—'}</Row>
            </dl>
          </div>
        </div>

        {paymentConfirmationStatus === 'CONFIRMED' ? (
          <div className="mt-3 rounded-lg border border-[var(--color-success)] bg-[color-mix(in_srgb,var(--color-success)_10%,transparent)] p-3 text-sm text-[var(--color-success)]">
            ✓ Confirmed Revenue Recovered — {currency.format(amountRecovered)}
            {op.testConfirmation && (
              <div className="mt-1 text-xs font-semibold uppercase tracking-wide">{op.testConfirmation.label}</div>
            )}
          </div>
        ) : (
          <div className="mt-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3 text-sm text-[var(--color-text-secondary)]">
            <p className="font-medium text-[var(--color-text-primary)]">Provider execution ≠ confirmed payment.</p>
            <p className="mt-1">
              {op.execution
                ? op.execution.executed
                  ? 'The provider call ran — this confirms execution, not confirmed payment. amountRecovered stays ₹0.00 until a real, verified provider webhook confirms it.'
                  : (op.execution.executionNote ?? scenario.safetyExplanation)
                : scenario.safetyExplanation}
            </p>
            {executed && provider === 'mock' && (
              <div className="mt-3">
                <ActionButton
                  onClick={handleConfirmTestPayment}
                  loading={confirmTestPaymentAction.loading}
                  variant="secondary"
                >
                  Confirm via signed webhook (TEST/SIMULATION)
                </ActionButton>
                <p className="mt-1.5 text-xs text-[var(--color-text-secondary)]">
                  Drives a real, signed payload through the actual PaymentConfirmationService pipeline - never a
                  real Razorpay payment. Requires RAZORPAY_WEBHOOK_SECRET to be configured in this environment.
                </p>
                {confirmTestPaymentAction.error && (
                  <InlineError error={confirmTestPaymentAction.error} onRetry={handleConfirmTestPayment} />
                )}
              </div>
            )}
            {executed && provider === 'razorpay' && paymentLinkUrl && (
              <div className="mt-3 rounded-lg border border-[var(--color-accent)] bg-[color-mix(in_srgb,var(--color-accent)_10%,transparent)] p-3">
                <div className="text-xs font-semibold uppercase tracking-wide text-[var(--color-accent)]">
                  Payment Link Created ≠ Payment Recovered
                </div>
                <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
                  A real Razorpay Test Mode Payment Link was created for this transaction. This recovery stays
                  pending — it only becomes recovered once Razorpay sends a verified webhook confirming the
                  customer actually paid it.
                </p>
                <a
                  href={paymentLinkUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-[var(--color-accent)] px-3.5 py-2 text-sm font-medium text-[var(--color-accent)] transition-colors hover:bg-[color-mix(in_srgb,var(--color-accent)_15%,transparent)]"
                >
                  Open Payment Link (Razorpay Test Mode) ↗
                </a>
              </div>
            )}
          </div>
        )}
      </section>

      {/* -------------------------------------------------- audit */}
      <section className="mt-5 border-t border-[var(--color-border)] pt-5">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Audit timeline</h4>
          <ActionButton onClick={handleRefreshAudit} loading={auditAction.loading}>
            Refresh audit
          </ActionButton>
        </div>
        {auditAction.error && <InlineError error={auditAction.error} onRetry={handleRefreshAudit} />}
        <div className="mt-2">
          <AuditTimeline entries={auditEntries} />
        </div>
      </section>
    </div>
  )
}
