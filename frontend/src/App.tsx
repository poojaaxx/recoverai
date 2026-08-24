import { useEffect, useState } from 'react'
import { apiClient } from './lib/api'

type HealthStatus = {
  status: string
  service: string
  timestamp: string
}

function App() {
  const [health, setHealth] = useState<HealthStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    apiClient
      .get<HealthStatus>('/api/health')
      .then((res) => setHealth(res.data))
      .catch(() => setError('Backend unreachable'))
  }, [])

  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-4 p-8 text-center">
      <h1 className="text-3xl font-semibold text-[var(--color-text-primary)]">
        RecoverAI
      </h1>
      <p className="text-[var(--color-text-secondary)]">
        Detect revenue at risk. Decide the right intervention. Recover it safely.
      </p>

      <div className="mt-6 rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] px-6 py-4 text-sm">
        {health && (
          <span className="text-[var(--color-success)]">
            Backend: {health.status} ({health.service})
          </span>
        )}
        {error && <span className="text-[var(--color-danger)]">{error}</span>}
        {!health && !error && (
          <span className="text-[var(--color-text-secondary)]">
            Checking backend connection…
          </span>
        )}
      </div>
    </div>
  )
}

export default App
