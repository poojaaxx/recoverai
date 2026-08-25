import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import { humanizeActor, humanizeEventType } from '../components/AuditTimeline'
import type { GlobalAuditPage } from '../types/recovery'

/** P1.4 — the portfolio-wide "everything the system has decided and done" feed, so a judge doesn't have to already know a specific transaction id to see the audit trail is real and complete. */
export function AuditFeedPage() {
  const [page, setPage] = useState<GlobalAuditPage | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)
  const [pageNumber, setPageNumber] = useState(0)
  const [eventType, setEventType] = useState('')
  const [actor, setActor] = useState('')

  const load = () => {
    setLoading(true)
    setError(null)
    api
      .globalAudit({
        page: pageNumber,
        size: 25,
        eventType: eventType || undefined,
        actor: actor || undefined,
      })
      .then((res) => setPage(res.data))
      .catch((err) => setError(toApiError(err)))
      .finally(() => setLoading(false))
  }

  useEffect(load, [pageNumber, eventType, actor])

  return (
    <div className="mx-auto max-w-5xl px-6 py-10">
      <Link to="/demo/recovery" className="text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]">
        ← RecoverAI
      </Link>
      <h1 className="mt-1 text-2xl font-semibold text-[var(--color-text-primary)]">Audit activity</h1>
      <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
        Every risk detection, AI recommendation, policy decision, execution, and payment confirmation the system has
        recorded, across every transaction — not just one you already know the id of.
      </p>

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <select
          value={eventType}
          onChange={(e) => {
            setPageNumber(0)
            setEventType(e.target.value)
          }}
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-3 py-1.5 text-sm text-[var(--color-text-primary)]"
        >
          <option value="">All event types</option>
          <option value="RECOVERY_AI_RECOMMENDATION">AI recommendation</option>
          <option value="RECOVERY_POLICY_EVALUATED">Policy evaluated</option>
          <option value="RECOVERY_EXECUTION_STARTED">Execution started</option>
          <option value="RECOVERY_EXECUTION_COMPLETED">Execution completed</option>
          <option value="RECOVERY_EXECUTION_FAILED">Execution failed</option>
          <option value="RECOVERY_EXECUTION_BLOCKED">Execution blocked</option>
          <option value="RECOVERY_EXECUTION_ESCALATED">Execution escalated</option>
          <option value="RECOVERY_EXECUTION_STOPPED">Execution stopped</option>
          <option value="PAYMENT_WEBHOOK_RECEIVED">Payment webhook received</option>
          <option value="PAYMENT_CONFIRMATION_VERIFIED">Payment confirmation verified</option>
          <option value="PAYMENT_RECOVERY_CONFIRMED">Payment recovery confirmed</option>
          <option value="PAYMENT_CONFIRMATION_REJECTED">Payment confirmation rejected</option>
        </select>
        <select
          value={actor}
          onChange={(e) => {
            setPageNumber(0)
            setActor(e.target.value)
          }}
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-3 py-1.5 text-sm text-[var(--color-text-primary)]"
        >
          <option value="">All actors</option>
          <option value="POLICY_ENGINE">Policy Engine</option>
          <option value="AI_AGENT">AI Agent</option>
          <option value="RECOVERY_EXECUTION_SERVICE">Execution Service</option>
          <option value="PAYMENT_CONFIRMATION_SERVICE">Confirmation Service</option>
        </select>
        <button
          onClick={load}
          disabled={loading}
          className="ml-auto rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)] disabled:opacity-50"
        >
          {loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      {error && (
        <div className="mt-4 rounded-lg border border-[var(--color-danger)] bg-[var(--color-surface-1)] px-4 py-3 text-sm text-[var(--color-danger)]">
          {error.message}
        </div>
      )}

      {page && (
        <>
          <div className="mt-4 overflow-x-auto rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)]">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-[var(--color-border)] text-xs uppercase tracking-wide text-[var(--color-text-secondary)]">
                  <th className="px-4 py-2">Event</th>
                  <th className="px-4 py-2">Actor</th>
                  <th className="px-4 py-2">Decision</th>
                  <th className="px-4 py-2">Transaction</th>
                  <th className="px-4 py-2">When</th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((entry) => (
                  <tr key={entry.id} className="border-b border-[var(--color-border)] last:border-0">
                    <td className="px-4 py-2">
                      <div className="text-[var(--color-text-primary)]">{humanizeEventType(entry.eventType)}</div>
                      <div className="font-mono text-[10px] text-[var(--color-text-secondary)]">{entry.eventType}</div>
                    </td>
                    <td className="px-4 py-2 text-[var(--color-text-secondary)]">{humanizeActor(entry.actor)}</td>
                    <td className="px-4 py-2 text-[var(--color-text-secondary)]">{entry.decision ?? '—'}</td>
                    <td className="px-4 py-2">
                      <Link
                        to={`/transactions/${entry.transactionId}`}
                        className="font-mono text-xs text-[var(--color-accent)] hover:underline"
                      >
                        {entry.externalTransactionId}
                      </Link>
                    </td>
                    <td className="px-4 py-2 text-[var(--color-text-secondary)]">
                      {new Date(entry.timestamp).toLocaleString()}
                    </td>
                  </tr>
                ))}
                {page.content.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-4 py-6 text-center text-[var(--color-text-secondary)]">
                      No audit events match this filter.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className="mt-3 flex items-center justify-between text-sm text-[var(--color-text-secondary)]">
            <span>
              Page {page.number + 1} of {Math.max(page.totalPages, 1)} — {page.totalElements} total events
            </span>
            <div className="flex gap-2">
              <button
                onClick={() => setPageNumber((p) => Math.max(0, p - 1))}
                disabled={page.number === 0}
                className="rounded-lg border border-[var(--color-border)] px-3 py-1 disabled:opacity-50"
              >
                Previous
              </button>
              <button
                onClick={() => setPageNumber((p) => p + 1)}
                disabled={page.number + 1 >= page.totalPages}
                className="rounded-lg border border-[var(--color-border)] px-3 py-1 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}
