import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import { useAsyncAction } from '../hooks/useAsyncAction'
import { Badge, riskTone } from './Badge'
import type { TransactionListItem } from '../types/transaction'

const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })

/**
 * Phase 14, section 8 - a portfolio-wide escalation inbox, not just the
 * single-transaction review already on the detail page. Lists every
 * currently ESCALATED transaction (real data, `GET /api/transactions`)
 * with Approve/Reject actions that call the exact same safe backend
 * workflow the detail page uses - approval never itself authorizes
 * execution, it re-runs the full AI + policy pipeline fresh.
 */
export function EscalationQueuePanel() {
  const [items, setItems] = useState<TransactionListItem[] | null>(null)
  const [actingOn, setActingOn] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const loadAction = useAsyncAction<TransactionListItem[]>(() =>
    api.transactions({ status: 'ESCALATED', size: 25, sort: 'AMOUNT_DESC' }).then((r) => r.data.content),
  )

  async function handleLoad() {
    const content = await loadAction.run()
    if (content) setItems(content)
  }

  async function handleApprove(id: string) {
    setActingOn(id)
    setActionError(null)
    try {
      await api.approveEscalation(id)
      await handleLoad()
    } catch {
      setActionError('Could not approve this escalation. Nothing was executed.')
    } finally {
      setActingOn(null)
    }
  }

  async function handleReject(id: string) {
    setActingOn(id)
    setActionError(null)
    try {
      await api.rejectEscalation(id)
      await handleLoad()
    } catch {
      setActionError('Could not reject this escalation.')
    } finally {
      setActingOn(null)
    }
  }

  return (
    <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-[var(--color-text-primary)]">Escalation queue</h2>
          <p className="mt-0.5 text-xs text-[var(--color-text-secondary)]">
            Transactions awaiting human review. Approving re-runs the full AI + policy pipeline fresh and only
            executes if that fresh check still says ALLOW — it never bypasses safety.
          </p>
        </div>
        <button
          onClick={handleLoad}
          disabled={loadAction.loading}
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3.5 py-2 text-sm text-[var(--color-text-primary)] hover:opacity-90 disabled:opacity-50"
        >
          {loadAction.loading ? 'Loading…' : items ? 'Refresh queue' : 'Load escalation queue'}
        </button>
      </div>

      {loadAction.error && <p className="mt-3 text-sm text-[var(--color-danger)]">{loadAction.error.message}</p>}
      {actionError && <p className="mt-3 text-sm text-[var(--color-danger)]">{actionError}</p>}

      {items && items.length === 0 && (
        <p className="mt-3 text-sm text-[var(--color-text-secondary)]">No escalated transactions right now.</p>
      )}

      {items && items.length > 0 && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead>
              <tr className="text-xs uppercase tracking-wide text-[var(--color-text-secondary)]">
                <th className="pb-2 font-medium">Transaction</th>
                <th className="pb-2 font-medium">Amount</th>
                <th className="pb-2 font-medium">Risk</th>
                <th className="pb-2 font-medium">Created</th>
                <th className="pb-2 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {items.map((t) => (
                <tr key={t.id} className="border-t border-[var(--color-border)]">
                  <td className="py-2">
                    <Link to={`/transactions/${t.id}`} className="font-mono text-xs text-[var(--color-accent)] hover:underline">
                      {t.externalTransactionId}
                    </Link>
                  </td>
                  <td className="py-2">{currency.format(t.amount)}</td>
                  <td className="py-2">
                    {t.riskLevel ? <Badge tone={riskTone(t.riskLevel)}>{t.riskLevel}</Badge> : '—'}
                  </td>
                  <td className="py-2 text-xs text-[var(--color-text-secondary)]">{new Date(t.createdAt).toLocaleDateString()}</td>
                  <td className="py-2">
                    <div className="flex gap-1.5">
                      <button
                        onClick={() => handleApprove(t.id)}
                        disabled={actingOn === t.id}
                        className="rounded-md bg-[var(--color-accent)] px-2.5 py-1 text-xs font-medium text-[var(--color-surface-0)] hover:opacity-90 disabled:opacity-50"
                      >
                        Approve → re-evaluate
                      </button>
                      <button
                        onClick={() => handleReject(t.id)}
                        disabled={actingOn === t.id}
                        className="rounded-md border border-[var(--color-border)] bg-[var(--color-surface-2)] px-2.5 py-1 text-xs text-[var(--color-text-primary)] hover:opacity-90 disabled:opacity-50"
                      >
                        Reject
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
