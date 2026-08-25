import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import { Badge, riskTone } from '../components/Badge'
import type { RecoveryMetrics, RiskMetrics } from '../types/recovery'
import type {
  PaymentMethod,
  TransactionListFilters,
  TransactionListPage,
  TransactionSort,
  TransactionStatus,
} from '../types/transaction'

const currency = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })
const percent = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 1 })
const PAGE_SIZE = 20

const STATUSES: TransactionStatus[] = ['SUCCESS', 'FAILED', 'PENDING', 'ABANDONED', 'RECOVERED', 'ESCALATED', 'STOPPED']
const RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const
const FAILURE_CATEGORIES = [
  'TEMPORARY_FAILURE',
  'INSUFFICIENT_FUNDS',
  'BANK_DECLINED',
  'NETWORK_ERROR',
  'AUTHENTICATION_FAILURE',
  'LIMIT_EXCEEDED',
  'UNKNOWN',
]
const PAYMENT_METHODS: PaymentMethod[] = ['CARD', 'UPI', 'NETBANKING', 'WALLET', 'EMI']
const SORTS: { value: TransactionSort; label: string }[] = [
  { value: 'NEWEST', label: 'Newest first' },
  { value: 'OLDEST', label: 'Oldest first' },
  { value: 'AMOUNT_DESC', label: 'Highest amount' },
  { value: 'RISK_SCORE_DESC', label: 'Highest risk' },
  { value: 'AMOUNT_AT_RISK_DESC', label: 'Highest amount at risk' },
  { value: 'RECOVERY_PROBABILITY_DESC', label: 'Highest recovery probability' },
]

function statusTone(status: TransactionStatus) {
  switch (status) {
    case 'SUCCESS':
    case 'RECOVERED':
      return 'success' as const
    case 'PENDING':
    case 'ESCALATED':
      return 'warning' as const
    case 'FAILED':
    case 'STOPPED':
      return 'danger' as const
    default:
      return 'neutral' as const
  }
}

function Kpi({ label, value, tone }: { label: string; value: string; tone?: 'success' | 'accent' }) {
  return (
    <div className="rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-4">
      <div className="text-xs font-medium uppercase tracking-wide text-[var(--color-text-secondary)]">{label}</div>
      <div
        className={`mt-1.5 text-xl font-semibold ${tone === 'success' ? 'text-[var(--color-success)]' : tone === 'accent' ? 'text-[var(--color-accent)]' : 'text-[var(--color-text-primary)]'}`}
      >
        {value}
      </div>
    </div>
  )
}

export function TransactionsPage() {
  const [filters, setFilters] = useState<TransactionListFilters>({ sort: 'NEWEST', page: 0, size: PAGE_SIZE })
  const [searchInput, setSearchInput] = useState('')
  const [page, setPage] = useState<TransactionListPage | null>(null)
  const [riskMetrics, setRiskMetrics] = useState<RiskMetrics | null>(null)
  const [recoveryMetrics, setRecoveryMetrics] = useState<RecoveryMetrics | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(false)

  const load = (nextFilters: TransactionListFilters) => {
    setLoading(true)
    setError(null)
    api
      .transactions(nextFilters)
      .then((res) => setPage(res.data))
      .catch((err) => setError(toApiError(err)))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load(filters)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters])

  useEffect(() => {
    api.riskMetrics().then((res) => setRiskMetrics(res.data)).catch(() => undefined)
    api.recoveryMetrics().then((res) => setRecoveryMetrics(res.data)).catch(() => undefined)
  }, [page === null])

  function updateFilter<K extends keyof TransactionListFilters>(key: K, value: TransactionListFilters[K]) {
    setFilters((prev) => ({ ...prev, [key]: value, page: 0 }))
  }

  function applySearch() {
    updateFilter('search', searchInput.trim() === '' ? undefined : searchInput.trim())
  }

  function goToPage(next: number) {
    setFilters((prev) => ({ ...prev, page: next }))
  }

  return (
    <div className="mx-auto max-w-7xl px-6 py-10">
      <div className="flex items-start justify-between gap-4">
        <div>
          <Link to="/" className="text-xs text-[var(--color-text-secondary)] hover:text-[var(--color-accent)]">
            ← RecoverAI
          </Link>
          <h1 className="mt-1 text-3xl font-semibold text-[var(--color-text-primary)]">Transactions</h1>
          <p className="mt-1 text-[var(--color-text-secondary)]">
            Every transaction in the database, searchable and filterable — not just the 5 demo scenarios.
          </p>
        </div>
        <Link
          to="/demo/recovery"
          className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-2 text-sm text-[var(--color-text-primary)] hover:bg-[var(--color-surface-2)]"
        >
          Demo console →
        </Link>
      </div>

      {(riskMetrics || recoveryMetrics) && (
        <div className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {riskMetrics && <Kpi label="Total transactions" value={String(riskMetrics.totalTransactions)} />}
          {riskMetrics && <Kpi label="Total transaction value" value={currency.format(riskMetrics.totalTransactionValue)} />}
          {riskMetrics && <Kpi label="Collected" value={currency.format(riskMetrics.totalRevenueCollected)} />}
          {riskMetrics && <Kpi label="Revenue at risk" value={currency.format(riskMetrics.revenueAtRisk)} />}
          {riskMetrics && <Kpi label="Potentially recoverable" value={currency.format(riskMetrics.potentiallyRecoverableRevenue)} tone="accent" />}
          {recoveryMetrics && (
            <Kpi label="Confirmed recovered (verified)" value={currency.format(recoveryMetrics.confirmedRecoveredRevenue)} tone="success" />
          )}
          {recoveryMetrics && <Kpi label="Recovery rate" value={percent.format(recoveryMetrics.recoveryRate)} />}
          {recoveryMetrics && <Kpi label="Transactions recovered" value={String(recoveryMetrics.transactionsRecovered)} />}
          {recoveryMetrics && <Kpi label="Escalated" value={String(recoveryMetrics.transactionsEscalated)} />}
          {recoveryMetrics && <Kpi label="Stopped" value={String(recoveryMetrics.transactionsStopped)} />}
        </div>
      )}

      <div className="mt-6 rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)] p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex-1 min-w-[220px]">
            <label className="text-xs text-[var(--color-text-secondary)]">Search (id, external id, or customer id)</label>
            <div className="mt-1 flex gap-2">
              <input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && applySearch()}
                placeholder="txn_..., a UUID, ..."
                className="w-full rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] outline-none focus:border-[var(--color-accent)]"
              />
              <button
                onClick={applySearch}
                className="rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-2)] px-3 py-1.5 text-sm text-[var(--color-text-primary)] hover:opacity-90"
              >
                Search
              </button>
            </div>
          </div>

          <FilterSelect label="Status" value={filters.status} onChange={(v) => updateFilter('status', v as TransactionStatus | undefined)} options={STATUSES} />
          <FilterSelect label="Risk level" value={filters.riskLevel} onChange={(v) => updateFilter('riskLevel', v as never)} options={[...RISK_LEVELS]} />
          <FilterSelect label="Failure category" value={filters.failureCategory} onChange={(v) => updateFilter('failureCategory', v)} options={FAILURE_CATEGORIES} />
          <FilterSelect label="Payment method" value={filters.paymentMethod} onChange={(v) => updateFilter('paymentMethod', v as PaymentMethod | undefined)} options={PAYMENT_METHODS} />

          <div>
            <label className="text-xs text-[var(--color-text-secondary)]">Sort</label>
            <select
              value={filters.sort}
              onChange={(e) => updateFilter('sort', e.target.value as TransactionSort)}
              className="mt-1 block rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-2 py-1.5 text-sm text-[var(--color-text-primary)]"
            >
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-4">
          <div className="flex gap-3">
            <label className="flex items-center gap-1.5 text-xs text-[var(--color-text-secondary)]">
              <input type="checkbox" checked={!!filters.atRiskOnly} onChange={(e) => updateFilter('atRiskOnly', e.target.checked || undefined)} />
              At-risk only
            </label>
            <label className="flex items-center gap-1.5 text-xs text-[var(--color-text-secondary)]">
              <input type="checkbox" checked={!!filters.recoveredOnly} onChange={(e) => updateFilter('recoveredOnly', e.target.checked || undefined)} />
              Recovered only
            </label>
          </div>
          <div className="flex items-center gap-2 text-xs text-[var(--color-text-secondary)]">
            <span>Amount</span>
            <input
              type="number"
              placeholder="min"
              value={filters.minAmount ?? ''}
              onChange={(e) => updateFilter('minAmount', e.target.value === '' ? undefined : Number(e.target.value))}
              className="w-24 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-2 py-1"
            />
            <span>–</span>
            <input
              type="number"
              placeholder="max"
              value={filters.maxAmount ?? ''}
              onChange={(e) => updateFilter('maxAmount', e.target.value === '' ? undefined : Number(e.target.value))}
              className="w-24 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-2 py-1"
            />
          </div>
          {(filters.status || filters.riskLevel || filters.failureCategory || filters.paymentMethod || filters.atRiskOnly || filters.recoveredOnly || filters.minAmount != null || filters.maxAmount != null || filters.search) && (
            <button
              onClick={() => {
                setSearchInput('')
                setFilters({ sort: 'NEWEST', page: 0, size: PAGE_SIZE })
              }}
              className="ml-auto text-xs text-[var(--color-accent)] hover:underline"
            >
              Clear filters
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-[var(--color-danger)] bg-[var(--color-surface-1)] px-4 py-3 text-sm text-[var(--color-danger)]">
          <span>{error.message}</span>
          <button onClick={() => load(filters)} className="ml-auto rounded-md border border-current px-3 py-1 text-xs hover:opacity-80">
            Retry
          </button>
        </div>
      )}

      <div className="mt-4 overflow-x-auto rounded-xl border border-[var(--color-border)] bg-[var(--color-surface-1)]">
        <table className="w-full min-w-[900px] text-left text-sm">
          <thead>
            <tr className="border-b border-[var(--color-border)] text-xs uppercase tracking-wide text-[var(--color-text-secondary)]">
              <th className="px-4 py-3">External ID</th>
              <th className="px-4 py-3">Amount</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Risk</th>
              <th className="px-4 py-3">Recovery probability</th>
              <th className="px-4 py-3">Amount at risk</th>
              <th className="px-4 py-3">Latest recovery</th>
              <th className="px-4 py-3">Created</th>
            </tr>
          </thead>
          <tbody>
            {loading && (
              <tr>
                <td colSpan={8} className="px-4 py-6 text-center text-[var(--color-text-secondary)]">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && page?.content.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-6 text-center text-[var(--color-text-secondary)]">
                  No transactions match these filters.
                </td>
              </tr>
            )}
            {!loading &&
              page?.content.map((t) => (
                <tr key={t.id} className="border-b border-[var(--color-border)] last:border-0 hover:bg-[var(--color-surface-2)]">
                  <td className="px-4 py-3">
                    <Link to={`/transactions/${t.id}`} className="font-mono text-xs text-[var(--color-accent)] hover:underline">
                      {t.externalTransactionId}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-[var(--color-text-primary)]">{currency.format(t.amount)}</td>
                  <td className="px-4 py-3">
                    <Badge tone={statusTone(t.status)}>{t.status}</Badge>
                  </td>
                  <td className="px-4 py-3">
                    {t.riskLevel ? <Badge tone={riskTone(t.riskLevel)}>{t.riskLevel}</Badge> : <span className="text-[var(--color-text-secondary)]">Not analyzed</span>}
                  </td>
                  <td className="px-4 py-3 text-[var(--color-text-secondary)]">
                    {t.recoveryProbability != null ? percent.format(t.recoveryProbability) : '—'}
                  </td>
                  <td className="px-4 py-3 text-[var(--color-text-secondary)]">
                    {t.amountAtRisk != null ? currency.format(t.amountAtRisk) : '—'}
                  </td>
                  <td className="px-4 py-3 text-[var(--color-text-secondary)]">
                    {t.latestRecoveryAction ? `${t.latestRecoveryAction} (${t.latestRecoveryStatus})` : 'No attempts yet'}
                  </td>
                  <td className="px-4 py-3 text-[var(--color-text-secondary)]">{new Date(t.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      {page && page.totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between text-sm text-[var(--color-text-secondary)]">
          <span>
            Page {page.number + 1} of {page.totalPages} · {page.totalElements} transactions
          </span>
          <div className="flex gap-2">
            <button
              disabled={page.number <= 0}
              onClick={() => goToPage(page.number - 1)}
              className="rounded-lg border border-[var(--color-border)] px-3 py-1.5 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Previous
            </button>
            <button
              disabled={page.number + 1 >= page.totalPages}
              onClick={() => goToPage(page.number + 1)}
              className="rounded-lg border border-[var(--color-border)] px-3 py-1.5 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Next
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function FilterSelect<T extends string>({
  label,
  value,
  onChange,
  options,
}: {
  label: string
  value: T | undefined
  onChange: (value: T | undefined) => void
  options: readonly T[]
}) {
  return (
    <div>
      <label className="text-xs text-[var(--color-text-secondary)]">{label}</label>
      <select
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? undefined : (e.target.value as T))}
        className="mt-1 block rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-0)] px-2 py-1.5 text-sm text-[var(--color-text-primary)]"
      >
        <option value="">All</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </div>
  )
}
