import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { clearSession, getSession } from '../lib/auth'
import { api } from '../lib/api'

/**
 * Route guard - redirects to `/login` when no session token is present.
 * This is a UX convenience only, not a security boundary: every protected
 * endpoint enforces authentication/authorization itself (see backend
 * SecurityConfig), so a request this component lets through can still be
 * rejected by the API with a 401/403 - this component just avoids showing
 * a page full of failed-request errors to a logged-out visitor.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation()
  const session = getSession()

  if (!session) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }

  return (
    <>
      <AuthBar username={session.username} role={session.role} />
      {children}
    </>
  )
}

function AuthBar({ username, role }: { username: string; role: string }) {
  return (
    <div className="flex items-center justify-end gap-3 border-b border-[var(--color-border)] bg-[var(--color-surface-1)] px-4 py-2 text-xs text-[var(--color-text-secondary)]">
      <span>
        {username} · <span className="font-medium">{role}</span>
      </span>
      <button
        onClick={() => {
          // Best-effort server-side revocation (invalidates this token, and every other
          // previously issued token, via AppUser.tokenVersion) - local logout proceeds
          // regardless of whether this call succeeds, so a logged-out browser is never
          // stuck waiting on (or blocked by) a flaky network request.
          api.logout().catch(() => {})
          clearSession()
          window.location.href = '/login'
        }}
        className="rounded border border-[var(--color-border)] px-2 py-1 hover:bg-[var(--color-surface-0)]"
      >
        Log out
      </button>
    </div>
  )
}
