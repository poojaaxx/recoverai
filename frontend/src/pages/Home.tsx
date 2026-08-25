import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, toApiError, type ApiError } from '../lib/api'
import type { HealthStatus } from '../types/recovery'

export function Home() {
  const [health, setHealth] = useState<HealthStatus | null>(null)
  const [error, setError] = useState<ApiError | null>(null)

  useEffect(() => {
    api
      .health()
      .then((res) => setHealth(res.data))
      .catch((err) => setError(toApiError(err)))
  }, [])

  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-4 p-8 text-center">
      <h1 className="text-3xl font-semibold text-[var(--color-text-primary)]">
        RecoverAI
      </h1>
      <p className="text-[var(--color-text-secondary)]">
        Detect revenue at risk. Decide the right intervention. Recover it safely.
      </p>

      <div className="mt-2 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-6 py-4 text-sm">
        {health && (
          <span className="text-[var(--color-success)]">
            Backend: {health.status} ({health.service})
          </span>
        )}
        {error && <span className="text-[var(--color-danger)]">{error.message}</span>}
        {!health && !error && (
          <span className="text-[var(--color-text-secondary)]">
            Checking backend connection…
          </span>
        )}
      </div>

      <Link
        to="/demo/recovery"
        className="mt-4 rounded-lg bg-[var(--color-accent)] px-5 py-2.5 text-sm font-medium text-[var(--color-surface-0)] hover:opacity-90"
      >
        Open the recovery demo →
      </Link>
    </div>
  )
}
