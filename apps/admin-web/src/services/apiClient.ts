import { API_BASE_URL } from '../config/env.ts'
import type { TokenPairResponse } from '../types/auth.ts'
import { clearSession, readSession, writeSession } from './sessionStorage.ts'

interface ErrorEnvelope {
  errorCode?: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly errorCode: string

  constructor(
    status: number,
    errorCode: string,
  ) {
    super(errorCode)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

type SessionExpiredListener = () => void

let refreshPromise: Promise<string> | null = null
const sessionExpiredListeners = new Set<SessionExpiredListener>()

export function onSessionExpired(listener: SessionExpiredListener): () => void {
  sessionExpiredListeners.add(listener)
  return () => sessionExpiredListeners.delete(listener)
}

function expireSession(): void {
  clearSession()
  sessionExpiredListeners.forEach((listener) => listener())
}

async function readError(response: Response): Promise<ApiError> {
  let errorCode = response.status === 401 ? 'UNAUTHENTICATED' : 'REQUEST_FAILED'

  try {
    const body = (await response.json()) as ErrorEnvelope
    if (typeof body.errorCode === 'string') {
      errorCode = body.errorCode
    }
  } catch {
    // The technical fallback is stable and intentionally ignores raw response text.
  }

  return new ApiError(response.status, errorCode)
}

async function refreshAccessToken(): Promise<string> {
  if (refreshPromise) {
    return refreshPromise
  }

  refreshPromise = (async () => {
    const session = readSession()
    if (!session) {
      expireSession()
      throw new ApiError(401, 'SESSION_EXPIRED')
    }

    let response: Response
    try {
      response = await fetch(`${API_BASE_URL}/auth/token-refreshes`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
      })
    } catch {
      throw new ApiError(0, 'NETWORK_ERROR')
    }

    if (!response.ok) {
      const error = await readError(response)
      expireSession()
      throw error
    }

    const tokens = (await response.json()) as TokenPairResponse
    writeSession(tokens)
    return tokens.accessToken
  })().finally(() => {
    refreshPromise = null
  })

  return refreshPromise
}

interface ApiRequestOptions extends RequestInit {
  authenticated?: boolean
  retryAfterRefresh?: boolean
}

export async function apiRequest<T>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { authenticated = true, retryAfterRefresh = true, headers, ...requestInit } = options
  const requestHeaders = new Headers(headers)
  const session = readSession()

  if (requestInit.body && !requestHeaders.has('Content-Type')) {
    requestHeaders.set('Content-Type', 'application/json')
  }
  if (authenticated && session) {
    requestHeaders.set('Authorization', `Bearer ${session.accessToken}`)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...requestInit,
      headers: requestHeaders,
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR')
  }

  if (response.status === 401 && authenticated && retryAfterRefresh) {
    const accessToken = await refreshAccessToken()
    requestHeaders.set('Authorization', `Bearer ${accessToken}`)
    return apiRequest<T>(path, {
      ...requestInit,
      headers: requestHeaders,
      authenticated,
      retryAfterRefresh: false,
    })
  }

  if (!response.ok) {
    const error = await readError(response)
    if (response.status === 401 && authenticated) {
      expireSession()
    }
    throw error
  }

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

export function isRetryableApiError(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500)
}
