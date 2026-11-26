import { API_BASE_URL } from '@/config/env';
import { apiErrorMessage } from '@/localization/displayLabels';
import { StaleStorageMutationError, tokenStorage } from '@/services/storage';
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
let tokenEpoch = 0;
let capabilityEpoch = 0;

/** Invalidates any in-flight token refresh or session-bound retry from writing storage/expiring a newer session. */
export function invalidateAuthSession(): void {
  tokenEpoch += 1;
  capabilityEpoch += 1;
  refreshPromise = null;
}

export function invalidateCapabilitySession(): void {
  capabilityEpoch += 1;
}

export interface AuthSessionBinding {
  authEpoch: number;
  capabilityEpoch: number;
}

export function getAuthSessionBinding(): AuthSessionBinding {
  return { authEpoch: tokenEpoch, capabilityEpoch };
}

interface RequestOptions extends Omit<RequestInit, 'body'> {
  requiresAuth?: boolean;
  body?: unknown;
  _isRetry?: boolean;
  expectedAuthEpoch?: number;
  expectedCapabilityEpoch?: number;
  retryOn401?: boolean;
  shouldContinue?: () => boolean;
}

function createAbortError(): Error {
  const abortError = new Error('The operation was aborted');
  abortError.name = 'AbortError';
  return abortError;
}

/**
 * Parses response safely, extracting ErrorResponse when available.
 */
async function parseResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get('content-type') || '';
  const isJson = contentType.includes('application/json');

  if (!response.ok) {
    let errorResponse: ErrorResponse | undefined;

    let rawData: unknown = undefined;
    if (isJson) {
      try {
        const data = await response.json();
        rawData = data;
        if (data && typeof data === 'object') {
          if ('errorCode' in data && 'message' in data) {
            errorResponse = data as ErrorResponse;
          }
        }
      } catch {
        // Fallback to generic message
      }
    }

    const retryAfterHeader = response.headers.get('Retry-After');
    const parsedRetryAfter = retryAfterHeader ? Number.parseInt(retryAfterHeader, 10) : NaN;
    const retryAfterSeconds = Number.isFinite(parsedRetryAfter) && parsedRetryAfter > 0 ? parsedRetryAfter : undefined;

    throw new ApiError(response.status, apiErrorMessage({ status: response.status, errorResponse }, 'Yêu cầu không thành công. Vui lòng thử lại.'), errorResponse, rawData, retryAfterSeconds);
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  if (isJson) {
    return (await response.json()) as T;
  }

  return (await response.text()) as unknown as T;
}

function staleAuthSessionError(): ApiError {
  return new ApiError(401, 'Phiên đăng nhập đã thay đổi.');
}

/**
 * Executes a single-flight token refresh scoped to expectedEpoch and the active refreshToken.
 */
async function performTokenRefresh(expectedEpoch: number): Promise<TokenPairResponse> {
  const refreshToken = await tokenStorage.getRefreshToken();
  if (expectedEpoch !== tokenEpoch) {
    throw staleAuthSessionError();
  }
  if (!refreshToken) {
    if (expectedEpoch === tokenEpoch) {
      await tokenStorage.clearTokens({ shouldApply: () => expectedEpoch === tokenEpoch });
      if (expectedEpoch === tokenEpoch) {
        sessionExpiredHandler?.();
      }
    }
    throw new ApiError(401, 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
  }

  const isSameRefreshSession = async (): Promise<boolean> =>
    expectedEpoch === tokenEpoch && (await tokenStorage.getRefreshToken()) === refreshToken;

  let staleSession = false;
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
    if (!(await isSameRefreshSession())) {
      staleSession = true;
      throw staleAuthSessionError();
    }
    await tokenStorage.saveTokens(tokenPair.accessToken, tokenPair.refreshToken, {
      shouldApply: () => expectedEpoch === tokenEpoch,
    });
    if (expectedEpoch !== tokenEpoch) {
      throw staleAuthSessionError();
    }
    return tokenPair;
  } catch (error) {
    if (error instanceof StaleStorageMutationError) {
      throw staleAuthSessionError();
    }
    if (!staleSession && (await isSameRefreshSession())) {
      await tokenStorage.clearTokens({ shouldApply: () => expectedEpoch === tokenEpoch });
      if (expectedEpoch === tokenEpoch) {
        sessionExpiredHandler?.();
      }
    }
    throw error;
  }
}

/**
 * Core HTTP request runner with automatic Bearer token injection and single-flight 401 refresh.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    requiresAuth = true,
    body,
    _isRetry = false,
    expectedAuthEpoch,
    expectedCapabilityEpoch,
    retryOn401 = true,
    shouldContinue,
    headers = {},
    ...rest
  } = options;
  const isSessionBindingValid = () =>
    (expectedAuthEpoch === undefined || expectedAuthEpoch === tokenEpoch) &&
    (expectedCapabilityEpoch === undefined || expectedCapabilityEpoch === capabilityEpoch);
  if (!isSessionBindingValid()) {
    throw staleAuthSessionError();
  }
  if (rest.signal?.aborted || (shouldContinue && !shouldContinue())) {
    throw createAbortError();
  }
  const requestEpoch = expectedAuthEpoch ?? tokenEpoch;

  const requestHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...(headers as Record<string, string>),
  };

  if (body !== undefined && !(body instanceof FormData)) {
    requestHeaders['Content-Type'] = 'application/json';
  }

  if (requiresAuth) {
    const accessToken = await tokenStorage.getAccessToken();
    if (requestEpoch !== tokenEpoch || !isSessionBindingValid()) {
      throw staleAuthSessionError();
    }
    if (shouldContinue && !shouldContinue()) {
      throw createAbortError();
    }
    if (accessToken) {
      requestHeaders.Authorization = `Bearer ${accessToken}`;
    }
  }

  if (rest.signal?.aborted || (shouldContinue && !shouldContinue())) {
    throw createAbortError();
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
    throw new ApiError(0, 'Không thể kết nối mạng. Vui lòng kiểm tra kết nối Internet.');
  }

  // Intercept 401 for authenticated requests (and only if not already retried)
  if (response.status === 401 && requiresAuth && !_isRetry) {
    if (requestEpoch !== tokenEpoch || !isSessionBindingValid()) {
      throw staleAuthSessionError();
    }
    if (!retryOn401) {
      return parseResponse<T>(response);
    }
    if (rest.signal?.aborted || (shouldContinue && !shouldContinue())) {
      throw createAbortError();
    }

    try {
      if (!refreshPromise) {
        const activeRefresh = performTokenRefresh(requestEpoch).finally(() => {
          if (refreshPromise === activeRefresh) {
            refreshPromise = null;
          }
        });
        refreshPromise = activeRefresh;
      }
      await refreshPromise;

      if (requestEpoch !== tokenEpoch || !isSessionBindingValid()) {
        throw staleAuthSessionError();
      }

      if (rest.signal?.aborted || (shouldContinue && !shouldContinue())) {
        throw createAbortError();
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
  const requestEpoch = tokenEpoch;
  const accessToken = await tokenStorage.getAccessToken();
  if (requestEpoch !== tokenEpoch) {
    throw staleAuthSessionError();
  }
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      headers: { Authorization: `Bearer ${accessToken ?? ''}`, 'Cache-Control': 'no-store' },
    });
  } catch {
    throw new ApiError(0, 'Không thể kết nối mạng.');
  }
  if (response.status === 401 && !retried) {
    if (requestEpoch !== tokenEpoch) {
      throw staleAuthSessionError();
    }
    if (!refreshPromise) {
      const activeRefresh = performTokenRefresh(requestEpoch).finally(() => {
        if (refreshPromise === activeRefresh) {
          refreshPromise = null;
        }
      });
      refreshPromise = activeRefresh;
    }
    await refreshPromise;
    if (requestEpoch !== tokenEpoch) {
      throw staleAuthSessionError();
    }
    return downloadAuthenticated(path, true);
  }
  if (!response.ok) {
    await parseResponse(response);
    throw new ApiError(response.status, 'Không thể tải tệp đính kèm.');
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
    invalidateAuthSession();
    const loginEpoch = tokenEpoch;
    const tokenPair = await request<TokenPairResponse>('/auth/sessions', {
      method: 'POST',
      requiresAuth: false,
      body: data,
    });
    if (loginEpoch !== tokenEpoch) {
      throw staleAuthSessionError();
    }
    try {
      await tokenStorage.saveTokens(tokenPair.accessToken, tokenPair.refreshToken, {
        shouldApply: () => loginEpoch === tokenEpoch,
      });
    } catch (error) {
      if (error instanceof StaleStorageMutationError) {
        throw staleAuthSessionError();
      }
      throw error;
    }
    if (loginEpoch !== tokenEpoch) {
      throw staleAuthSessionError();
    }
    return tokenPair;
  },

  async logout(): Promise<void> {
    invalidateAuthSession();
    const logoutEpoch = tokenEpoch;
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
      if (logoutEpoch === tokenEpoch) {
        await tokenStorage.clearTokens({
          shouldApply: () => logoutEpoch === tokenEpoch,
        });
      }
    }
  },

  async getCurrentUser(): Promise<CurrentUserResponse> {
    return request<CurrentUserResponse>('/users/me', {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
