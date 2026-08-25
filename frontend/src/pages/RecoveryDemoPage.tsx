import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import { Badge, outcomeTone, policyTone, riskTone } from '../components/Badge'
import { ScenarioOperations } from '../components/ScenarioOperations'
import { outcomeLabel, type RecoveryDemoScenario, type RecoveryDemoSummary } from '../types/demo'

const currency = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 2,
})

function KpiCard({ label, value, hint, tone }: { label: string; value: string; hint: string; tone?: 'accent' | 'success' }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">{label}</div>
      <div
        className={`mt-2 text-2xl font-semibold ${tone === 'success' ? 'text-[var(--color-success)]' : tone === 'accent' ? 'text-[var(--color-accent)]' : 'text-[var(--color-text-primary)]'}`}
      >
        {value}
      </div>
      <div className="mt-1 text-xs text-[var(--color-text-secondary)]">{hint}</div>
    </div>
  )
}

function PipelineDiagram() {
  const steps = ['Payment Failure', 'Risk Detection', 'AI Recommendation', 'Safety Policy']
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="flex flex-wrap items-center justify-center gap-2 text-sm">
        {steps.map((step, i) => (
          <div key={step} className="flex items-center gap-2">
            <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3 py-1.5 text-[var(--color-text-primary)]">
              {step}
            </div>
            {i < steps.length - 1 && <span className="text-[var(--color-text-secondary)]">→</span>}
          </div>
        ))}
        <span className="text-[var(--color-text-secondary)]">→</span>
        <div className="rounded-lg border border-[var(--color-accent)] px-3 py-1.5 font-medium text-[var(--color-accent)]">ALLOW?</div>
      </div>
      <div className="mt-3 flex flex-wrap items-center justify-center gap-6 text-sm">
        <div className="flex items-center gap-2">
          <Badge tone="success">YES</Badge>
          <span className="text-[var(--color-text-secondary)]">→</span>
          <div className="rounded-lg border border-[var(--color-success)] px-3 py-1.5 text-[var(--color-success)]">Execute Payment</div>
          <span className="text-[var(--color-text-secondary)]">→</span>
          <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3 py-1.5">Confirmation</div>
          <span className="text-[var(--color-text-secondary)]">→</span>
          <div className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3 py-1.5">Audit</div>
        </div>
        <div className="flex items-center gap-2">
          <Badge tone="danger">NO</Badge>
          <span className="text-[var(--color-text-secondary)]">→</span>
          <div className="rounded-lg border border-[var(--color-warning)] px-3 py-1.5 text-[var(--color-warning)]">
            Escalate / Block / Stop
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

export function RecoveryDemoPage() {
  const [summary, setSummary] = useState<RecoveryDemoSummary | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false)
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null)

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
            <KpiCard
              label="Transactions at risk"
              value={String(summary.atRiskScenarios)}
              hint={`${summary.allowedCount} allowed · ${summary.escalatedCount} escalated · ${summary.blockedCount} blocked · ${summary.stoppedCount} stopped`}
            />
          </div>

          <div className="mt-6">
            <PipelineDiagram />
          </div>

          <div className="mt-8 grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_1.4fr]">
            <div className="space-y-3">
              {summary.scenarios.map((s) => (
                <ScenarioCard
                  key={s.scenarioLabel}
                  scenario={s}
                  selected={s.scenarioLabel === selectedLabel}
                  onSelect={() => setSelectedLabel(s.scenarioLabel)}
                />
              ))}
            </div>
            <div>{selected && <ScenarioOperations scenario={selected} onDashboardRefreshNeeded={load} />}</div>
          </div>
        </>
      )}
    </div>
  )
}
