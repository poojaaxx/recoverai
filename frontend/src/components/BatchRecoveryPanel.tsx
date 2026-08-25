import { useState } from 'react'
import { api } from '../lib/api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { Badge, batchOutcomeTone } from './Badge'
import type { BatchExecutionResponse } from '../types/recovery'
import type { TransactionListItem } from '../types/transaction'

const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })

/**
 * Phase 14, section 9 - the judge-facing bounded batch recovery demo.
 * Real transactions only (fetched from the same `/api/transactions` the
 * dashboard uses), a real confirmation step showing exactly what will be
 * sent before anything executes, and a real per-item result breakdown
 * from `POST /api/recovery/batch/execute` - nothing here is scripted or
 * pre-computed client-side. MERCHANT_ADMIN only (server-enforced; a
 * non-admin sees the real 403 from `toApiError`).
 */
export function BatchRecoveryPanel() {
  const [candidates, setCandidates] = useState<TransactionListItem[] | null>(null)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [confirming, setConfirming] = useState(false)
  const [result, setResult] = useState<BatchExecutionResponse | null>(null)

  const loadCandidatesAction = useAsyncAction<TransactionListItem[]>(() =>
    api.transactions({ status: 'FAILED', size: 15, sort: 'AMOUNT_AT_RISK_DESC' }).then((r) => r.data.content),
  )
  const executeAction = useAsyncAction<BatchExecutionResponse>(() =>
    api.executeBatch({ transactionIds: Array.from(selected) }).then((r) => r.data),
  )

  async function handleLoadCandidates() {
    setResult(null)
    const content = await loadCandidatesAction.run()
    if (content) {
      setCandidates(content)
      setSelected(new Set(content.map((t) => t.id)))
      setConfirming(false)
    }
  }

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function handleConfirmExecute() {
    const res = await executeAction.run()
    if (res) {
      setResult(res)
      setConfirming(false)
    }
  }

  const selectedTransactions = candidates?.filter((t) => selected.has(t.id)) ?? []
  const estimatedAggregate = selectedTransactions.reduce((sum, t) => sum + t.amount, 0)

  return (
    <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">Bounded batch recovery</h2>
          <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
            Every transaction is reloaded and re-run through the full AI + policy pipeline immediately before
            execution — the server decides what actually executes, never this list. MERCHANT_ADMIN only.
          </p>
        </div>
        <button
          onClick={handleLoadCandidates}
          disabled={loadCandidatesAction.loading}
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3.5 py-2 text-sm text-[var(--color-text-primary)] hover:opacity-90 disabled:opacity-50"
        >
          {loadCandidatesAction.loading ? 'Loading…' : candidates ? 'Reload candidates' : 'Load failed transactions'}
        </button>
      </div>

      {loadCandidatesAction.error && <p className="mt-3 text-sm text-[var(--color-danger)]">{loadCandidatesAction.error.message}</p>}

      {candidates && candidates.length === 0 && (
        <p className="mt-3 text-sm text-[var(--color-text-secondary)]">No FAILED transactions available right now.</p>
      )}

      {candidates && candidates.length > 0 && (
        <>
          <div className="mt-4 max-h-64 space-y-1.5 overflow-y-auto">
            {candidates.map((t) => (
              <label
                key={t.id}
                className="flex cursor-pointer items-center justify-between gap-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-3 py-1.5 text-sm"
              >
                <span className="flex items-center gap-2">
                  <input type="checkbox" checked={selected.has(t.id)} onChange={() => toggle(t.id)} />
                  <span className="font-mono text-xs text-[var(--color-text-secondary)]">{t.externalTransactionId}</span>
                </span>
                <span className="text-[var(--color-text-primary)]">{currency.format(t.amount)}</span>
              </label>
            ))}
          </div>

          <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-sm">
            <span className="text-[var(--color-text-secondary)]">
              {selected.size} selected · estimated aggregate {currency.format(estimatedAggregate)}
            </span>
            <button
              onClick={() => setConfirming(true)}
              disabled={selected.size === 0}
              className="rounded-lg bg-[var(--color-accent)] px-3.5 py-2 text-sm font-medium text-[var(--color-surface-0)] hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              Review batch execution
            </button>
          </div>
        </>
      )}

      {confirming && (
        <div className="mt-4 rounded-lg border border-[var(--color-warning)] bg-[color-mix(in_srgb,var(--color-warning)_8%,transparent)] p-4 text-sm">
          <div className="font-semibold text-[var(--color-text-primary)]">Confirm bounded batch execution</div>
          <dl className="mt-2 space-y-1">
            <div className="flex justify-between">
              <dt className="text-[var(--color-text-secondary)]">Transactions selected</dt>
              <dd>{selected.size}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-[var(--color-text-secondary)]">Estimated aggregate amount</dt>
              <dd>{currency.format(estimatedAggregate)}</dd>
            </div>
          </dl>
          <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
            Each transaction is re-evaluated fresh on the server; only those the policy still authorizes as ALLOW
            will actually execute, and the portfolio's aggregate/count safety limits are enforced there too — this
            list is only a selection, not a guarantee of what will run.
          </p>
          {executeAction.error && <p className="mt-2 text-[var(--color-danger)]">{executeAction.error.message}</p>}
          <div className="mt-3 flex gap-2">
            <button
              onClick={handleConfirmExecute}
              disabled={executeAction.loading}
              className="rounded-lg bg-[var(--color-danger)] px-3.5 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
            >
              {executeAction.loading ? 'Executing…' : 'Confirm & execute'}
            </button>
            <button
              onClick={() => setConfirming(false)}
              className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3.5 py-2 text-sm text-[var(--color-text-primary)] hover:opacity-90"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {result && (
        <div className="mt-4 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] p-4 text-sm">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Executed</div>
              <div className="text-lg font-semibold text-[var(--color-success)]">{result.executedCount}</div>
            </div>
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Blocked</div>
              <div className="text-lg font-semibold text-[var(--color-text-primary)]">{result.blockedCount}</div>
            </div>
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Escalated</div>
              <div className="text-lg font-semibold text-[var(--color-warning)]">{result.escalatedCount}</div>
            </div>
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Stopped</div>
              <div className="text-lg font-semibold text-[var(--color-text-primary)]">{result.stoppedCount}</div>
            </div>
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Skipped (portfolio limit)</div>
              <div className="text-lg font-semibold text-[var(--color-text-primary)]">{result.skippedPortfolioLimitCount}</div>
            </div>
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Failed provider call</div>
              <div className="text-lg font-semibold text-[var(--color-danger)]">{result.failedProviderCallCount}</div>
            </div>
            <div>
              <div className="text-xs text-[var(--color-text-secondary)]">Aggregate executed</div>
              <div className="text-lg font-semibold text-[var(--color-text-primary)]">
                {currency.format(result.aggregateAmountExecuted)} / {currency.format(result.maxAggregateAmount)}
              </div>
            </div>
          </div>
          <p className="mt-2 text-xs text-[var(--color-text-secondary)]">
            Execution success is not confirmed revenue — only a subsequent verified webhook confirmation moves the
            confirmed-recovery figures above. Duplicate ids in the request: {result.duplicateRequestCount}.
          </p>
          <div className="mt-3 max-h-64 space-y-1.5 overflow-y-auto">
            {result.results.map((item) => (
              <div key={item.transactionId} className="flex items-center justify-between gap-2 rounded-md border border-[var(--color-border)] px-2.5 py-1.5">
                <span className="font-mono text-xs text-[var(--color-text-secondary)]">
                  {item.externalTransactionId ?? item.transactionId}
                </span>
                <span className="flex items-center gap-2 text-xs text-[var(--color-text-secondary)]">
                  {item.reason && <span className="max-w-xs truncate" title={item.reason}>{item.reason}</span>}
                  <Badge tone={batchOutcomeTone(item.outcome)}>{item.outcome.replace(/_/g, ' ')}</Badge>
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
