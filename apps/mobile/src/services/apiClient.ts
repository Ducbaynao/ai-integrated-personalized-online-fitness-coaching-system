import { API_BASE_URL } from '@/config/env';
import { tokenStorage } from '@/services/storage';
import {
  ApiError,
  ConfirmEmailRequest,
  CurrentUserResponse,
  ErrorResponse,
  LoginRequest,
  LogoutRequest,
  RefreshTokenRequest,
  RegisterRequest,
  RegistrationResponse,
  TokenPairResponse,
} from '@/types/auth';
export { ApiError } from '@/types/auth';

type SessionExpiredHandler = () => void;
let sessionExpiredHandler: SessionExpiredHandler | null = null;

export function registerSessionExpiredHandler(handler: SessionExpiredHandler | null): void {
  sessionExpiredHandler = handler;
}

// Single-flight refresh mutex to prevent concurrent refresh requests (avoiding refresh replay detection)
let refreshPromise: Promise<TokenPairResponse> | null = null;

interface RequestOptions extends Omit<RequestInit, 'body'> {
  requiresAuth?: boolean;
  body?: unknown;
  _isRetry?: boolean;
}

/**
 * Parses response safely, extracting ErrorResponse when available.
 */
async function parseResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get('content-type') || '';
  const isJson = contentType.includes('application/json');

  if (!response.ok) {
    let errorResponse: ErrorResponse | undefined;
    let errorMessage = `Request failed with status ${response.status}`;

    let rawData: unknown = undefined;
    if (isJson) {
      try {
        const data = await response.json();
        rawData = data;
        if (data && typeof data === 'object') {
          if ('errorCode' in data && 'message' in data) {
            errorResponse = data as ErrorResponse;
            errorMessage = errorResponse.message;
          } else if ('error' in data && data.error && typeof data.error === 'object' && 'message' in data.error) {
            errorMessage = String((data.error as any).message);
          } else if ('message' in data && typeof data.message === 'string') {
            errorMessage = data.message;
          }
        }
      } catch {
        // Fallback to generic message
      }
    }

    throw new ApiError(response.status, errorMessage, errorResponse, rawData);
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  if (isJson) {
    return (await response.json()) as T;
  }

  return (await response.text()) as unknown as T;
}

/**
 * Executes a single-flight token refresh.
 */
async function performTokenRefresh(): Promise<TokenPairResponse> {
  const refreshToken = await tokenStorage.getRefreshToken();
  if (!refreshToken) {
    await tokenStorage.clearTokens();
    sessionExpiredHandler?.();
    throw new ApiError(401, 'No refresh token available');
  }

  try {
    const response = await fetch(`${API_BASE_URL}/auth/token-refreshes`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({ refreshToken } as RefreshTokenRequest),
    });

    const tokenPair = await parseResponse<TokenPairResponse>(response);
    await tokenStorage.saveTokens(tokenPair.accessToken, tokenPair.refreshToken);
    return tokenPair;
  } catch (error) {
    await tokenStorage.clearTokens();
    sessionExpiredHandler?.();
    throw error;
  }
}

/**
 * Core HTTP request runner with automatic Bearer token injection and single-flight 401 refresh.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { requiresAuth = true, body, _isRetry = false, headers = {}, ...rest } = options;

  const requestHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...(headers as Record<string, string>),
  };

  if (body !== undefined && !(body instanceof FormData)) {
    requestHeaders['Content-Type'] = 'application/json';
  }

  if (requiresAuth) {
    const accessToken = await tokenStorage.getAccessToken();
    if (accessToken) {
      requestHeaders.Authorization = `Bearer ${accessToken}`;
    }
  }

  const url = `${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;

  let response: Response;
  try {
    response = await fetch(url, {
      ...rest,
      headers: requestHeaders,
      body: body instanceof FormData ? body : body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (err: unknown) {
    if (rest.signal?.aborted || (err instanceof Error && err.name === 'AbortError')) {
      throw err;
    }
    throw new ApiError(0, 'Network connection failed. Please check your internet connection.');
  }

  // Intercept 401 for authenticated requests (and only if not already retried)
  if (response.status === 401 && requiresAuth && !_isRetry) {
    if (rest.signal?.aborted) {
      const abortError = new Error('The operation was aborted');
      abortError.name = 'AbortError';
      throw abortError;
    }

    try {
      if (!refreshPromise) {
        refreshPromise = performTokenRefresh().finally(() => {
          refreshPromise = null;
        });
      }
      await refreshPromise;

      if (rest.signal?.aborted) {
        const abortError = new Error('The operation was aborted');
        abortError.name = 'AbortError';
        throw abortError;
      }

      // Retry request once with the new access token
      return await request<T>(path, {
        ...options,
        _isRetry: true,
      });
    } catch (refreshErr) {
      throw refreshErr;
    }
  }

  return parseResponse<T>(response);
}

/** Authenticated binary read. Never expose a public storage URL or cache the response. */
export async function downloadAuthenticated(path: string, retried = false): Promise<Uint8Array> {
  const accessToken = await tokenStorage.getAccessToken();
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      headers: { Authorization: `Bearer ${accessToken ?? ''}`, 'Cache-Control': 'no-store' },
    });
  } catch {
    throw new ApiError(0, 'Network connection failed');
  }
  if (response.status === 401 && !retried) {
    if (!refreshPromise) refreshPromise = performTokenRefresh().finally(() => { refreshPromise = null; });
    await refreshPromise;
    return downloadAuthenticated(path, true);
  }
  if (!response.ok) {
    await parseResponse(response);
    throw new ApiError(response.status, 'Attachment unavailable');
  }
  return new Uint8Array(await response.arrayBuffer());
}

/**
 * Specialized Auth and User API methods.
 */
export const authApi = {
  async register(data: RegisterRequest): Promise<RegistrationResponse> {
    return request<RegistrationResponse>('/auth/registrations', {
      method: 'POST',
      requiresAuth: false,
      body: data,
    });
  },

  async confirmEmail(data: ConfirmEmailRequest): Promise<void> {
    return request<void>('/auth/email-verifications/confirmations', {
      method: 'POST',
      requiresAuth: false,
      body: data,
    });
  },

  async login(data: LoginRequest): Promise<TokenPairResponse> {
    const tokenPair = await request<TokenPairResponse>('/auth/sessions', {
      method: 'POST',
      requiresAuth: false,
      body: data,
    });
    await tokenStorage.saveTokens(tokenPair.accessToken, tokenPair.refreshToken);
    return tokenPair;
  },

  async logout(): Promise<void> {
    const refreshToken = await tokenStorage.getRefreshToken();
    try {
      if (refreshToken) {
        await request<void>('/auth/sessions', {
          method: 'DELETE',
          requiresAuth: true,
          body: { refreshToken } as LogoutRequest,
        });
      }
    } catch {
      // Swallowed on purpose: client logout must succeed even if server revocation fails or network is down
    } finally {
      await tokenStorage.clearTokens();
    }
  },

  async getCurrentUser(): Promise<CurrentUserResponse> {
    return request<CurrentUserResponse>('/users/me', {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
