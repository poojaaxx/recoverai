import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api, toApiError } from '../lib/api'
import { saveSession } from '../lib/auth'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/demo/recovery'

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const res = await api.login(username, password)
      saveSession(res.data.token, res.data.role, username)
      navigate(redirectTo, { replace: true })
    } catch (err) {
      setError(toApiError(err).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-full flex-col items-center justify-center gap-6 p-8">
      <div className="text-center">
        <h1 className="text-2xl font-semibold text-[var(--color-text-primary)]">RecoverAI</h1>
        <p className="mt-1 text-sm text-[var(--color-text-secondary)]">Sign in to continue</p>
      </div>

      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm rounded-lg border border-[var(--color-border)] bg-[var(--color-surface-1)] p-6"
      >
        <label className="block text-sm font-medium text-[var(--color-text-primary)]">
          Username
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="mt-1 w-full rounded-md border border-[var(--color-border)] bg-[var(--color-surface-0)] px-3 py-2 text-sm"
            autoFocus
            required
          />
        </label>

        <label className="mt-4 block text-sm font-medium text-[var(--color-text-primary)]">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-1 w-full rounded-md border border-[var(--color-border)] bg-[var(--color-surface-0)] px-3 py-2 text-sm"
            required
          />
        </label>

        {error && <p className="mt-3 text-sm text-[var(--color-danger)]">{error}</p>}

        <button
          type="submit"
          disabled={loading}
          className="mt-5 w-full rounded-lg bg-[var(--color-accent)] px-4 py-2.5 text-sm font-medium text-[var(--color-surface-0)] hover:opacity-90 disabled:opacity-50"
        >
          {loading ? 'Signing in…' : 'Sign in'}
        </button>

        <p className="mt-4 text-xs text-[var(--color-text-secondary)]">
          Judge/demo login: use the credentials shared in the project README. MERCHANT_ADMIN
          can authorize recovery execution; OPERATOR is read/analyze-only.
        </p>
      </form>
    </div>
  )
}
