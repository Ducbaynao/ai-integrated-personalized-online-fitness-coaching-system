import { createContext, useContext } from 'react'
import type { CurrentUser } from '../../types/auth.ts'

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated' | 'error'

export interface AuthContextValue {
  status: AuthStatus
  user: CurrentUser | null
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => Promise<void>
  retryBootstrap: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return context
}
