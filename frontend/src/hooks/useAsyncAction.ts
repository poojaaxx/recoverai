import { useState } from 'react'
import { toApiError, type ApiError } from '../lib/api'

/**
 * Wraps a single backend call with loading/error state - used for every
 * button-triggered action in the interactive recovery console (Analyze
 * Risk, Get AI Recommendation, Evaluate Policy, Execute Recovery, audit
 * refresh, ...). Never invents a result: on failure, `error` is set and
 * the caller's own state is left untouched.
 */
export function useAsyncAction<T>(fn: () => Promise<T>) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  async function run(): Promise<T | null> {
    setLoading(true)
    setError(null)
    try {
      return await fn()
    } catch (err) {
      setError(toApiError(err))
      return null
    } finally {
      setLoading(false)
    }
  }

  return { run, loading, error, setError }
}
