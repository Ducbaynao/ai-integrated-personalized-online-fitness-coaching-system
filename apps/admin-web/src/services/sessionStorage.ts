import type { StoredSession, TokenPairResponse } from '../types/auth.ts'

const SESSION_STORAGE_KEY = 'fitness-admin-session'

export function readSession(): StoredSession | null {
  const serialized = sessionStorage.getItem(SESSION_STORAGE_KEY)

  if (!serialized) {
    return null
  }

  try {
    const parsed = JSON.parse(serialized) as Partial<StoredSession>
    if (typeof parsed.accessToken === 'string' && typeof parsed.refreshToken === 'string') {
      return { accessToken: parsed.accessToken, refreshToken: parsed.refreshToken }
    }
  } catch {
    // Invalid browser state is handled like an expired session.
  }

  clearSession()
  return null
}

export function writeSession(tokens: TokenPairResponse): void {
  sessionStorage.setItem(
    SESSION_STORAGE_KEY,
    JSON.stringify({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
    } satisfies StoredSession),
  )
}

export function clearSession(): void {
  sessionStorage.removeItem(SESSION_STORAGE_KEY)
}
