import { useCallback, useEffect, useMemo, useState } from 'react'
import type { PropsWithChildren } from 'react'
import type { CurrentUser } from '../../types/auth.ts'
import { ApiError, onSessionExpired } from '../../services/apiClient.ts'
import * as authApi from '../../services/authApi.ts'
import { queryClient } from '../../services/queryClient.ts'
import { clearSession, readSession, writeSession } from '../../services/sessionStorage.ts'
import { AuthContext } from './authContextValue.ts'
import type { AuthContextValue, AuthStatus } from './authContextValue.ts'

export function AuthProvider({ children }: PropsWithChildren) {
  const [status, setStatus] = useState<AuthStatus>(() =>
    readSession() ? 'loading' : 'unauthenticated',
  )
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [bootstrapAttempt, setBootstrapAttempt] = useState(0)

  const becomeUnauthenticated = useCallback(() => {
    clearSession()
    queryClient.clear()
    setUser(null)
    setStatus('unauthenticated')
  }, [])

  useEffect(() => onSessionExpired(becomeUnauthenticated), [becomeUnauthenticated])

  useEffect(() => {
    let active = true
    const storedSession = readSession()

    if (!storedSession) {
      return () => {
        active = false
      }
    }

    authApi
      .getCurrentUser()
      .then((currentUser) => {
        if (active) {
          setUser(currentUser)
          setStatus('authenticated')
        }
      })
      .catch((error: unknown) => {
        if (!active) return
        if (error instanceof ApiError && (error.status === 0 || error.status >= 500)) {
          setStatus('error')
          return
        }
        becomeUnauthenticated()
      })

    return () => {
      active = false
    }
  }, [becomeUnauthenticated, bootstrapAttempt])

  const signIn = useCallback(async (email: string, password: string) => {
    const tokens = await authApi.login({ email, password })
    writeSession(tokens)
    queryClient.clear()
    setUser(tokens.user)
    setStatus('authenticated')
  }, [])

  const signOut = useCallback(async () => {
    const session = readSession()
    try {
      if (session) {
        await authApi.logout(session.refreshToken)
      }
    } finally {
      becomeUnauthenticated()
    }
  }, [becomeUnauthenticated])

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      signIn,
      signOut,
      retryBootstrap: () => {
        setStatus('loading')
        setBootstrapAttempt((attempt) => attempt + 1)
      },
    }),
    [signIn, signOut, status, user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
