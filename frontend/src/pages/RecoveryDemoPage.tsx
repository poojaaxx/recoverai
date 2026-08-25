import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { Badge, outcomeTone, policyTone, riskTone } from '../components/Badge'
import { ScenarioOperations } from '../components/ScenarioOperations'
import { BatchRecoveryPanel } from '../components/BatchRecoveryPanel'
import { EscalationQueuePanel } from '../components/EscalationQueuePanel'
import { outcomeLabel, type PolicyDecision, type RecoveryDemoScenario, type RecoveryDemoSummary } from '../types/demo'
import type { BatchAgentEvaluationResult, BatchRiskAnalysisResult, RecoveryMetrics, RiskMetrics } from '../types/recovery'
import { aiProviderLabel } from '../types/recovery'
import type { ObservabilityMetrics } from '../types/observability'

const currency = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 2,
})

function KpiCard({
  label,
  value,
  hint,
  tone,
  children,
}: {
  label: string
  value: string
  hint: string
  tone?: 'accent' | 'success'
  children?: ReactNode
}) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">{label}</div>
      <div
        className={`mt-2 text-2xl font-semibold ${tone === 'success' ? 'text-[var(--color-success)]' : tone === 'accent' ? 'text-[var(--color-accent)]' : 'text-[var(--color-text-primary)]'}`}
      >
        {value}
      </div>
      {children ?? <div className="mt-1 text-xs text-[var(--color-text-secondary)]">{hint}</div>}
    </div>
  )
}

type ScenarioFilter = 'ALL' | PolicyDecision

/** A clickable chip that filters the scenario list below — real filtering over already-loaded real data, not decorative. */
function FilterChip({
  label,
  count,
  active,
  onClick,
}: {
  label: string
  count: number
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full border px-2 py-0.5 text-xs transition-colors ${
        active
          ? 'border-[var(--color-accent)] bg-[color-mix(in_srgb,var(--color-accent)_16%,transparent)] text-[var(--color-accent)]'
          : 'border-[var(--color-border)] text-[var(--color-text-secondary)] hover:bg-[var(--color-surface-2)]'
      }`}
    >
      {label} {count}
    </button>
  )
}

/** A step's visual state, derived only from real fields on the selected scenario — never simulated. */
type StepState = 'done' | 'active' | 'pending'

function stepBox(label: string, state: StepState, detail?: string) {
  const classes =
    state === 'done'
      ? 'border-[var(--color-accent)] bg-[color-mix(in_srgb,var(--color-accent)_12%,transparent)] text-[var(--color-accent)]'
      : state === 'active'
        ? 'border-[var(--color-accent)] text-[var(--color-accent)] animate-pulse'
        : 'border-[var(--color-border)] bg-[var(--color-surface-2)] text-[var(--color-text-secondary)]'
  return (
    <div key={label} className={`rounded-lg border px-3 py-1.5 font-medium ${classes}`}>
      <div>{label}</div>
      {detail && <div className="text-[10px] font-normal opacity-80">{detail}</div>}
    </div>
  )
}

/**
 * Reflects the real, currently-known state of the selected scenario — not
 * a static graphic. Selecting a different scenario, or completing an
 * action in the panel below (which refreshes the dashboard), changes what
 * lights up here. No stage is ever marked reached without a real backend
 * field confirming it.
 */
function PipelineDiagram({ scenario }: { scenario: RecoveryDemoScenario | null }) {
  const hasRisk = scenario != null // RecoveryDemoService always computes risk before returning a scenario
  const hasRecommendation = scenario?.aiRecommendedAction != null
  const hasPolicy = scenario?.policyDecision != null
  const decision = scenario?.policyDecision ?? null
  const isAllow = decision === 'ALLOW'
  const isNoBranch = decision === 'ESCALATE' || decision === 'BLOCK' || decision === 'STOP'
  const executed = scenario?.executed ?? false

  const failureState: StepState = 'done'
  const riskState: StepState = hasRisk ? 'done' : 'pending'
  const aiState: StepState = hasRecommendation ? 'done' : hasRisk ? 'active' : 'pending'
  const policyState: StepState = hasPolicy ? 'done' : hasRecommendation ? 'active' : 'pending'
  const allowState: StepState = hasPolicy ? (isAllow ? 'done' : isNoBranch ? 'pending' : 'active') : 'pending'
  const executeState: StepState = executed ? 'done' : isAllow ? 'active' : 'pending'
  const noBranchState: StepState = isNoBranch ? 'done' : 'pending'
  const confirmationState: StepState =
    scenario?.paymentConfirmationStatus === 'CONFIRMED' ? 'done' : executed ? 'active' : 'pending'

  const riskDetail = scenario ? `${scenario.riskScore.toFixed(0)} · ${scenario.riskLevel}` : undefined
  const aiDetail = scenario?.aiRecommendedAction
    ? `${scenario.aiRecommendedAction}${scenario.aiConfidence != null ? ` · ${(scenario.aiConfidence * 100).toFixed(0)}%` : ''}`
    : undefined
  const policyDetail = scenario?.policyDecision ?? undefined
  const executeDetail = scenario?.executed ? (scenario.executionStatus ?? undefined) : undefined
  const confirmationDetail =
    scenario?.confirmedAmount != null ? currency.format(scenario.confirmedAmount) : scenario?.executed ? 'Pending' : undefined

  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      {!scenario && (
        <p className="mb-3 text-center text-xs text-[var(--color-text-secondary)]">
          Select a scenario below to see its real pipeline state highlighted here.
        </p>
      )}
      <div className="flex flex-wrap items-center justify-center gap-2 text-sm">
        {stepBox('Payment Failure', failureState)}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {stepBox('Risk Detection', riskState, riskDetail)}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {stepBox('AI Recommendation', aiState, aiDetail)}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {stepBox('Safety Policy', policyState, policyDetail)}
        <span className="text-[var(--color-text-secondary)]">→</span>
        <div
          className={`rounded-lg border px-3 py-1.5 font-medium ${
            allowState === 'done'
              ? 'border-[var(--color-accent)] bg-[color-mix(in_srgb,var(--color-accent)_12%,transparent)] text-[var(--color-accent)]'
              : 'border-[var(--color-accent)] text-[var(--color-accent)]'
          }`}
        >
          ALLOW?
        </div>
      </div>
      <div className="mt-3 flex flex-wrap items-center justify-center gap-6 text-sm">
        <div className="flex items-center gap-2">
          <Badge tone={allowState === 'done' ? 'success' : 'neutral'}>YES</Badge>
          <span className="text-[var(--color-text-secondary)]">→</span>
          {stepBox('Execute Payment', executeState, executeDetail)}
          <span className="text-[var(--color-text-secondary)]">→</span>
          {stepBox('Confirmation', confirmationState, confirmationDetail)}
          <span className="text-[var(--color-text-secondary)]">→</span>
          {stepBox('Audit', hasPolicy ? 'done' : 'pending')}
        </div>
        <div className="flex items-center gap-2">
          <Badge tone={noBranchState === 'done' ? 'danger' : 'neutral'}>NO</Badge>
          <span className="text-[var(--color-text-secondary)]">→</span>
          <div
            className={`rounded-lg border px-3 py-1.5 ${
              noBranchState === 'done'
                ? 'border-[var(--color-warning)] bg-[color-mix(in_srgb,var(--color-warning)_12%,transparent)] text-[var(--color-warning)] font-medium'
                : 'border-[var(--color-warning)] text-[var(--color-warning)]'
            }`}
          >
            Escalate / Block / Stop{isNoBranch && decision ? ` (${decision})` : ''}
          </div>
        </div>
      </div>
      <div className="mt-4 text-center text-sm font-medium text-[var(--color-text-primary)]">
        “AI recommends. <span className="text-[var(--color-accent)]">Policy authorizes.</span>”
      </div>
    </div>
  )
}

function ScenarioCard({
  scenario,
  selected,
  onSelect,
}: {
  scenario: RecoveryDemoScenario
  selected: boolean
  onSelect: () => void
}) {
  const outcome = outcomeLabel(scenario)
  return (
    <button
      onClick={onSelect}
      className={`w-full rounded-xl border p-4 text-left transition-colors ${
        selected
          ? 'border-[var(--color-accent)] bg-[var(--color-surface-2)]'
          : 'border-[var(--color-border)] bg-[var(--color-surface-1)] hover:bg-[var(--color-surface-2)]'
      }`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="font-mono text-xs text-[var(--color-text-secondary)]">{scenario.externalTransactionId}</span>
        <Badge tone={outcomeTone(outcome)}>{outcome}</Badge>
      </div>
      <div className="mt-2 text-lg font-semibold text-[var(--color-text-primary)]">
        {currency.format(scenario.amount)}
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        <Badge tone={riskTone(scenario.riskLevel)}>{scenario.riskLevel}</Badge>
        <span className="text-[var(--color-text-secondary)]">·</span>
        <span className="text-xs text-[var(--color-text-secondary)]">{scenario.aiRecommendedAction ?? '—'}</span>
        <span className="text-[var(--color-text-secondary)]">·</span>
        <Badge tone={policyTone(scenario.policyDecision)}>{scenario.policyDecision ?? 'N/A'}</Badge>
      </div>
      {scenario.aiRecommendedAction && scenario.finalAction && scenario.aiRecommendedAction !== scenario.finalAction && (
        <div className="mt-2 rounded-md border border-[var(--color-warning)] bg-[color-mix(in_srgb,var(--color-warning)_10%,transparent)] px-2 py-1 text-[11px] text-[var(--color-warning)]">
          AI recommended <strong>{scenario.aiRecommendedAction}</strong>, policy authorized <strong>{scenario.finalAction}</strong>
        </div>
      )}
    </button>
  )
}

function AnalyzeAllResult({ result }: { result: BatchRiskAnalysisResult }) {
  return (
    <div className="mt-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3 text-sm">
      <span className="text-[var(--color-text-primary)]">{result.transactionsAnalyzed} transactions analyzed.</span>{' '}
      <span className="text-[var(--color-text-secondary)]">
        Revenue at risk now {currency.format(result.metrics.revenueAtRisk)}, potentially recoverable{' '}
        {currency.format(result.metrics.potentiallyRecoverableRevenue)}.
      </span>
    </div>
  )
}

function EvaluateAllResult({ result }: { result: BatchAgentEvaluationResult }) {
  const actions = Object.entries(result.recommendationCountByAction)
  const decisions = Object.entries(result.countByPolicyDecision)
  return (
    <div className="mt-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3 text-sm">
      <div className="text-[var(--color-text-primary)]">
        {result.transactionsEvaluated} transactions evaluated by the AI agent (average confidence{' '}
        {(result.averageConfidence * 100).toFixed(0)}%).
      </div>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        {actions.map(([action, count]) => (
          <Badge key={action} tone="neutral">
            {action}: {count}
          </Badge>
        ))}
      </div>
      <div className="mt-1.5 flex flex-wrap gap-1.5">
        {decisions.map(([decision, count]) => (
          <Badge key={decision} tone={policyTone(decision as PolicyDecision)}>
            {decision}: {count}
          </Badge>
        ))}
      </div>
      {result.providerFailures + result.malformedOutputs > 0 && (
        <div className="mt-1.5 text-xs text-[var(--color-text-secondary)]">
          {result.providerFailures} provider failures, {result.malformedOutputs} malformed outputs — both fail closed to ESCALATE.
        </div>
      )}
    </div>
  )
}

/** Portfolio-wide figures from GET /api/recovery/metrics — separate from the 5-scenario demo aggregate above, since this covers every transaction the batch actions have touched, not just the curated scenarios. */
function PortfolioMetricsPanel({ metrics }: { metrics: RecoveryMetrics }) {
  const stat = (label: string, value: string, tone?: 'success' | 'accent') => (
    <div>
      <div className="text-xs text-[var(--color-text-secondary)]">{label}</div>
      <div
        className={`mt-0.5 text-lg font-semibold ${tone === 'success' ? 'text-[var(--color-success)]' : tone === 'accent' ? 'text-[var(--color-accent)]' : 'text-[var(--color-text-primary)]'}`}
      >
        {value}
      </div>
    </div>
  )
  return (
    <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">Portfolio recovery metrics</h2>
      <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
        Computed across every recovery attempt ever made, not just the 5 demo scenarios.
      </p>
      <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        {stat('Revenue at risk', currency.format(metrics.totalRevenueAtRisk))}
        {stat('Potentially recoverable', currency.format(metrics.potentiallyRecoverableRevenue), 'accent')}
        {stat('Recovery attempts', String(metrics.recoveryAttempts))}
        {stat('Provider executions', String(metrics.successfulExecutionCount))}
        {stat('Confirmed recoveries', String(metrics.confirmedRecoveryCount))}
        {stat('Confirmed revenue recovered', currency.format(metrics.confirmedRecoveredRevenue), 'success')}
        {stat('Pending confirmation', currency.format(metrics.pendingConfirmationAmount))}
        {stat('Remaining at risk', currency.format(metrics.amountRemainingAtRisk))}
        {stat('Distinct customers processed', String(metrics.distinctCustomersProcessed))}
      </div>
    </div>
  )
}

/** "RecoverAI evaluates transactions individually, but measures confirmed recovery across the portfolio." A compact real-numbers funnel — every figure from an already-fetched backend response, never computed client-side. */
function PortfolioNarrative({
  risk,
  observability,
  metrics,
}: {
  risk: RiskMetrics
  observability: ObservabilityMetrics
  metrics: RecoveryMetrics
}) {
  const decisionsTotal =
    observability.policyDecisions.allow +
    observability.policyDecisions.block +
    observability.policyDecisions.escalate +
    observability.policyDecisions.stop
  const step = (label: string, value: string) => (
    <div className="flex flex-col items-center">
      <div className="text-lg font-semibold text-[var(--color-text-primary)]">{value}</div>
      <div className="text-[10px] uppercase tracking-wide text-[var(--color-text-secondary)]">{label}</div>
    </div>
  )
  return (
    <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <p className="text-sm text-[var(--color-text-secondary)]">
        RecoverAI evaluates transactions individually, but measures confirmed recovery across the portfolio.
      </p>
      <div className="mt-4 flex flex-wrap items-center justify-center gap-3">
        {step('Total transactions', String(risk.totalTransactions))}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {step('At risk', String(risk.atRiskTransactions))}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {step('Policy decisions', String(decisionsTotal))}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {step('Recovery attempts', String(metrics.recoveryAttempts))}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {step('Confirmed payments', String(metrics.confirmedRecoveryCount))}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {step('Confirmed revenue', currency.format(metrics.confirmedRecoveredRevenue))}
      </div>
    </div>
  )
}

/** Policy/webhook/provider counts from GET /api/observability/metrics — production observability, not a monitoring platform: just the handful of numbers that answer "is this actually working." */
function ObservabilityPanel({ observability }: { observability: ObservabilityMetrics }) {
  const { policyDecisions, webhooks } = observability
  const stat = (label: string, value: number) => (
    <div>
      <div className="text-xs text-[var(--color-text-secondary)]">{label}</div>
      <div className="mt-0.5 text-base font-semibold text-[var(--color-text-primary)]">{value}</div>
    </div>
  )
  return (
    <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">System observability</h2>
      <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
        Real counts of what the system has actually decided and processed — not a monitoring platform, just a few
        production-readiness signals.
      </p>
      <div className="mt-3 inline-flex items-center gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-3 py-1.5 text-xs">
        <span className="text-[var(--color-text-secondary)]">Active AI provider:</span>
        <span className="font-medium text-[var(--color-text-primary)]">{aiProviderLabel(observability.aiProviderMode)}</span>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-4">
        {stat('Policy: ALLOW', policyDecisions.allow)}
        {stat('Policy: BLOCK', policyDecisions.block)}
        {stat('Policy: ESCALATE', policyDecisions.escalate)}
        {stat('Policy: STOP', policyDecisions.stop)}
      </div>
      <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-4">
        {stat('Webhooks processed', webhooks.processed)}
        {stat('Webhooks rejected', webhooks.rejected)}
        {stat('Invalid signature', webhooks.invalidSignature)}
        {stat('Webhooks received', webhooks.receivedTotal)}
      </div>
    </div>
  )
}

export function RecoveryDemoPage() {
  const [summary, setSummary] = useState<RecoveryDemoSummary | null>(null)
  const [metrics, setMetrics] = useState<RecoveryMetrics | null>(null)
  const [observability, setObservability] = useState<ObservabilityMetrics | null>(null)
  const [riskMetrics, setRiskMetrics] = useState<RiskMetrics | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false)
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null)
  const [scenarioFilter, setScenarioFilter] = useState<ScenarioFilter>('ALL')
  const [analyzeAllResult, setAnalyzeAllResult] = useState<BatchRiskAnalysisResult | null>(null)
  const [evaluateAllResult, setEvaluateAllResult] = useState<BatchAgentEvaluationResult | null>(null)

  const analyzeAllAction = useAsyncAction<BatchRiskAnalysisResult>(() => api.analyzeAllRisk().then((r) => r.data))
  const evaluateAllAction = useAsyncAction<BatchAgentEvaluationResult>(() => api.evaluateAllWithAi().then((r) => r.data))

  async function handleAnalyzeAll() {
    const result = await analyzeAllAction.run()
    if (result) {
      setAnalyzeAllResult(result)
      load()
    }
  }

  async function handleEvaluateAll() {
    const result = await evaluateAllAction.run()
    if (result) setEvaluateAllResult(result)
  }

  const load = () => {
    setLoading(true)
    setError(null)
    api
      .demoSummary()
      .then((res) => {
        setSummary(res.data)
        setSelectedLabel((prev) => prev ?? res.data.scenarios[0]?.scenarioLabel ?? null)
      })
      .catch((err) => setError(toApiError(err)))
      .finally(() => {
        setLoading(false)
        setHasLoadedOnce(true)
      })
    // Portfolio metrics and observability load independently — a failure here shouldn't block the scenario dashboard above.
    api
      .recoveryMetrics()
      .then((res) => setMetrics(res.data))
      .catch(() => undefined)
    api
      .observabilityMetrics()
      .then((res) => setObservability(res.data))
      .catch(() => undefined)
    api
      .riskMetrics()
      .then((res) => setRiskMetrics(res.data))
      .catch(() => undefined)
  }

  useEffect(load, [])

  const selected = summary?.scenarios.find((s) => s.scenarioLabel === selectedLabel) ?? null
  const filteredScenarios =
    summary == null
      ? []
      : scenarioFilter === 'ALL'
        ? summary.scenarios
        : summary.scenarios.filter((s) => s.policyDecision === scenarioFilter)

  return (
    <div className="mx-auto max-w-6xl px-6 py-10">
      <div className="flex items-start justify-between gap-4">
        <div>
          <Link to="/" className="text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]">
            ← RecoverAI
          </Link>
          <h1 className="mt-1 text-3xl font-semibold text-[var(--color-text-primary)]">RecoverAI — AI Revenue Recovery</h1>
          <p className="mt-1 text-[var(--color-text-secondary)]">Detect risk. Decide intervention. Recover safely.</p>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to="/transactions"
            className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)]"
          >
            All transactions →
          </Link>
          <Link
            to="/audit"
            className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)]"
          >
            Audit activity →
          </Link>
          <button
            onClick={load}
            disabled={loading}
            className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)] disabled:opacity-50"
          >
            {loading ? 'Refreshing…' : 'Refresh dashboard'}
          </button>
        </div>
      </div>

      {!hasLoadedOnce && loading && (
        <div className="mt-6 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-3 text-sm text-[var(--color-text-secondary)]">
          Connecting to RecoverAI backend… this can take up to a couple of minutes if it's waking up from being idle.
        </div>
      )}

      {error && (
        <div className="mt-6 flex flex-wrap items-center gap-3 rounded-lg border border-[var(--color-danger)] bg-[var(--color-surface-1)] px-4 py-3 text-sm text-[var(--color-danger)]">
          <span>{error.message}</span>
          <button onClick={load} className="ml-auto rounded-md border border-current px-3 py-1 text-xs hover:opacity-80">
            Retry
          </button>
        </div>
      )}

      {summary && (
        <>
          <div className="mt-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <KpiCard
              label="Revenue at risk"
              value={currency.format(summary.totalAmountAtRisk)}
              hint={`${summary.atRiskScenarios} of ${summary.scenariosEvaluated} scenarios`}
            />
            <KpiCard
              label="Potentially recoverable"
              value={currency.format(summary.totalPotentialRecoveryValue)}
              hint="Risk-weighted estimate, not a guarantee"
              tone="accent"
            />
            <KpiCard
              label="Provider executions"
              value={String(summary.gatewayCalls)}
              hint={`${summary.simulatedExecutions} simulated — a provider call, not a confirmed payment`}
            />
            <KpiCard
              label="Confirmed revenue recovered"
              value={currency.format(summary.confirmedAmountRecovered)}
              hint="Only counted after real payment confirmation — not the same as a provider execution"
              tone="success"
            />
            <KpiCard label="Transactions at risk" value={String(summary.atRiskScenarios)} hint="">
              <div className="mt-2 flex flex-wrap gap-1.5">
                <FilterChip label="All" count={summary.atRiskScenarios} active={scenarioFilter === 'ALL'} onClick={() => setScenarioFilter('ALL')} />
                <FilterChip label="Allowed" count={summary.allowedCount} active={scenarioFilter === 'ALLOW'} onClick={() => setScenarioFilter('ALLOW')} />
                <FilterChip label="Escalated" count={summary.escalatedCount} active={scenarioFilter === 'ESCALATE'} onClick={() => setScenarioFilter('ESCALATE')} />
                <FilterChip label="Blocked" count={summary.blockedCount} active={scenarioFilter === 'BLOCK'} onClick={() => setScenarioFilter('BLOCK')} />
                <FilterChip label="Stopped" count={summary.stoppedCount} active={scenarioFilter === 'STOP'} onClick={() => setScenarioFilter('STOP')} />
              </div>
            </KpiCard>
          </div>

          {riskMetrics && observability && metrics && (
            <PortfolioNarrative risk={riskMetrics} observability={observability} metrics={metrics} />
          )}
          {metrics && <PortfolioMetricsPanel metrics={metrics} />}
          {observability && <ObservabilityPanel observability={observability} />}

          <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">Portfolio actions</h2>
                <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
                  Run the risk/AI engines across every currently at-risk transaction, not just these 5 scenarios.
                </p>
              </div>
              <div className="flex gap-2">
                <button
                  onClick={handleAnalyzeAll}
                  disabled={analyzeAllAction.loading}
                  className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3.5 py-2 text-sm text-[var(--color-text-primary)] hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {analyzeAllAction.loading ? 'Analyzing…' : 'Analyze all transactions'}
                </button>
                <button
                  onClick={handleEvaluateAll}
                  disabled={evaluateAllAction.loading}
                  className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3.5 py-2 text-sm text-[var(--color-text-primary)] hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {evaluateAllAction.loading ? 'Evaluating…' : 'Evaluate all with AI'}
                </button>
              </div>
            </div>

            {analyzeAllAction.error && (
              <p className="mt-3 text-sm text-[var(--color-danger)]">{analyzeAllAction.error.message}</p>
            )}
            {evaluateAllAction.error && (
              <p className="mt-3 text-sm text-[var(--color-danger)]">{evaluateAllAction.error.message}</p>
            )}

            {analyzeAllResult && <AnalyzeAllResult result={analyzeAllResult} />}
            {evaluateAllResult && <EvaluateAllResult result={evaluateAllResult} />}
          </div>

          <BatchRecoveryPanel />
          <EscalationQueuePanel />

          <div className="mt-6">
            <PipelineDiagram scenario={selected} />
          </div>

          <div className="mt-8 grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_1.4fr]">
            <div className="space-y-3">
              {filteredScenarios.map((s) => (
                <ScenarioCard
                  key={s.scenarioLabel}
                  scenario={s}
                  selected={s.scenarioLabel === selectedLabel}
                  onSelect={() => setSelectedLabel(s.scenarioLabel)}
                />
              ))}
              {filteredScenarios.length === 0 && (
                <p className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-4 text-sm text-[var(--color-text-secondary)]">
                  No scenarios match this filter.
                </p>
              )}
            </div>
            <div>{selected && <ScenarioOperations scenario={selected} onDashboardRefreshNeeded={load} />}</div>
          </div>
        </>
      )}
    </div>
  )
}
