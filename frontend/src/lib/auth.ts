const TOKEN_KEY = 'recoverai.auth.token'
const ROLE_KEY = 'recoverai.auth.role'
const USERNAME_KEY = 'recoverai.auth.username'

export type UserRole = 'MERCHANT_ADMIN' | 'OPERATOR'

export interface Session {
  token: string
  role: UserRole
  username: string
}

/**
 * Thin wrapper over localStorage for the JWT issued by `POST /api/auth/login`.
 * This module only stores and retrieves the token - it never decides
 * whether an action is authorized. Every real authorization decision still
 * happens server-side (see SecurityConfig); the role stored here is only
 * used to shape what the UI shows (e.g. hiding a button an OPERATOR would
 * get a 403 from anyway), never to grant or deny anything itself.
 */
export function saveSession(token: string, role: UserRole, username: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(ROLE_KEY, role)
  localStorage.setItem(USERNAME_KEY, username)
}

export function getSession(): Session | null {
  const token = localStorage.getItem(TOKEN_KEY)
  const role = localStorage.getItem(ROLE_KEY) as UserRole | null
  const username = localStorage.getItem(USERNAME_KEY)
  if (!token || !role || !username) return null
  return { token, role, username }
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem(USERNAME_KEY)
}

export function isAuthenticated(): boolean {
  return getSession() !== null
}
