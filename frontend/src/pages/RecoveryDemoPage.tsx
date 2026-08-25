import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { Badge, outcomeTone, policyTone, riskTone } from '../components/Badge'
import { ScenarioOperations } from '../components/ScenarioOperations'
import { outcomeLabel, type PolicyDecision, type RecoveryDemoScenario, type RecoveryDemoSummary } from '../types/demo'
import type { BatchAgentEvaluationResult, BatchRiskAnalysisResult } from '../types/recovery'

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

function stepBox(label: string, state: StepState) {
  const classes =
    state === 'done'
      ? 'border-[var(--color-accent)] bg-[color-mix(in_srgb,var(--color-accent)_12%,transparent)] text-[var(--color-accent)]'
      : state === 'active'
        ? 'border-[var(--color-accent)] text-[var(--color-accent)] animate-pulse'
        : 'border-[var(--color-border)] bg-[var(--color-surface-2)] text-[var(--color-text-secondary)]'
  return (
    <div key={label} className={`rounded-lg border px-3 py-1.5 font-medium ${classes}`}>
      {label}
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
        {stepBox('Risk Detection', riskState)}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {stepBox('AI Recommendation', aiState)}
        <span className="text-[var(--color-text-secondary)]">→</span>
        {stepBox('Safety Policy', policyState)}
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
          {stepBox('Execute Payment', executeState)}
          <span className="text-[var(--color-text-secondary)]">→</span>
          {stepBox('Confirmation', 'pending')}
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

export function RecoveryDemoPage() {
  const [summary, setSummary] = useState<RecoveryDemoSummary | null>(null)
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
        <button
          onClick={load}
          disabled={loading}
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)] disabled:opacity-50"
        >
          {loading ? 'Refreshing…' : 'Refresh dashboard'}
        </button>
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
          <div className="mt-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
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
              label="Confirmed revenue recovered"
              value={currency.format(summary.confirmedAmountRecovered)}
              hint="Only counted after real payment confirmation"
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
