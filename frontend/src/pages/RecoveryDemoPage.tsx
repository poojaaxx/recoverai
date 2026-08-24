import { useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { apiClient } from '../lib/api'
import { Badge, outcomeTone, policyTone, riskTone } from '../components/Badge'
import { outcomeLabel, type RecoveryDemoScenario, type RecoveryDemoSummary } from '../types/demo'

const currency = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 2,
})

const percent = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 0 })

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

function DetailPanel({ scenario }: { scenario: RecoveryDemoScenario }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-lg font-semibold text-[var(--color-text-primary)]">{scenario.externalTransactionId}</h3>
        <Badge tone={outcomeTone(outcomeLabel(scenario))}>{outcomeLabel(scenario)}</Badge>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <section>
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Risk analysis</h4>
          <dl className="mt-2 space-y-1 text-sm">
            <Row label="Risk score">{scenario.riskScore.toFixed(2)} / 100</Row>
            <Row label="Risk level">
              <Badge tone={riskTone(scenario.riskLevel)}>{scenario.riskLevel}</Badge>
            </Row>
            <Row label="Amount at risk">{currency.format(scenario.amountAtRisk)}</Row>
            <Row label="Recovery probability">{percent.format(scenario.recoveryProbability)}</Row>
            <Row label="Potential recovery value">{currency.format(scenario.potentialRecoveryValue)}</Row>
          </dl>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{scenario.riskReason}</p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {scenario.riskFactors.map((f) => (
              <Badge key={f} tone="neutral">
                {f}
              </Badge>
            ))}
          </div>
        </section>

        <section>
          <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">AI recommendation</h4>
          <dl className="mt-2 space-y-1 text-sm">
            <Row label="Recommended action">{scenario.aiRecommendedAction ?? '—'}</Row>
            <Row label="Confidence">{scenario.aiConfidence != null ? percent.format(scenario.aiConfidence) : '—'}</Row>
          </dl>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{scenario.aiRationale}</p>

          <h4 className="mt-4 text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Policy decision</h4>
          <dl className="mt-2 space-y-1 text-sm">
            <Row label="Decision">
              <Badge tone={policyTone(scenario.policyDecision)}>{scenario.policyDecision ?? 'N/A'}</Badge>
            </Row>
            <Row label="Requires human approval">{scenario.requiresHumanApproval ? 'Yes' : 'No'}</Row>
          </dl>
          <p className="mt-2 text-sm text-[var(--color-text-secondary)]">{scenario.policyReason}</p>
        </section>
      </div>

      <section className="mt-4">
        <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Execution result</h4>
        <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-3">
          <Row label="Executed">{scenario.executed ? 'Yes' : 'No'}</Row>
          <Row label="Provider">{scenario.provider ?? '—'}</Row>
          <Row label="Simulated">{scenario.simulated ? 'Yes' : 'No'}</Row>
          <Row label="Amount recovered">{currency.format(scenario.amountRecovered)}</Row>
          <Row label="Failure code">{scenario.failureCode ?? '—'}</Row>
          <Row label="Duplicate/replay">{scenario.duplicate ? 'Yes' : 'No'}</Row>
        </dl>
        <div className="mt-3 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-3 text-sm text-[var(--color-text-secondary)]">
          {scenario.safetyExplanation}
        </div>
      </section>

      <section className="mt-4">
        <h4 className="text-xs font-semibold uppercase tracking-wide text-[var(--color-text-secondary)]">Audit timeline</h4>
        <ol className="mt-2 space-y-2 border-l border-[var(--color-border)] pl-4">
          {scenario.auditTimeline.map((entry) => (
            <li key={entry.id} className="relative text-sm">
              <span className="absolute -left-[21px] top-1.5 h-2 w-2 rounded-full bg-[var(--color-accent)]" />
              <div className="font-mono text-xs text-[var(--color-accent)]">{entry.eventType}</div>
              <div className="text-[var(--color-text-secondary)]">
                {entry.actor}
                {entry.decision ? ` · ${entry.decision}` : ''} · {new Date(entry.timestamp).toLocaleTimeString()}
              </div>
              {entry.reason && <div className="mt-0.5 text-[var(--color-text-secondary)]">{entry.reason}</div>}
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-2">
      <dt className="text-[var(--color-text-secondary)]">{label}</dt>
      <dd className="text-right text-[var(--color-text-primary)]">{children}</dd>
    </div>
  )
}

export function RecoveryDemoPage() {
  const [summary, setSummary] = useState<RecoveryDemoSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [selectedLabel, setSelectedLabel] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    setError(null)
    apiClient
      .get<RecoveryDemoSummary>('/api/demo/recovery')
      .then((res) => {
        setSummary(res.data)
        setSelectedLabel((prev) => prev ?? res.data.scenarios[0]?.scenarioLabel ?? null)
      })
      .catch(() => setError('Could not reach the RecoverAI backend.'))
      .finally(() => setLoading(false))
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
          {loading ? 'Running…' : 'Run demo'}
        </button>
      </div>

      {error && (
        <div className="mt-6 rounded-lg border border-[var(--color-danger)] bg-[var(--color-surface-1)] px-4 py-3 text-sm text-[var(--color-danger)]">
          {error}
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
            <div>{selected && <DetailPanel scenario={selected} />}</div>
          </div>
        </>
      )}
    </div>
  )
}
