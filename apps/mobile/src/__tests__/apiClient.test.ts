import { authApi, registerSessionExpiredHandler, request } from '@/services/apiClient';
import { tokenStorage } from '@/services/storage';
import { ApiError, TokenPairResponse } from '@/types/auth';
import * as SecureStore from 'expo-secure-store';

// Global fetch mock
const originalFetch = globalThis.fetch;

describe('apiClient and authApi', () => {
  let fetchMock: jest.Mock;

  beforeEach(() => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    fetchMock = jest.fn();
    globalThis.fetch = fetchMock;
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  it('injects Bearer token into Authorization header when requiresAuth is true', async () => {
    await tokenStorage.saveTokens('mock-access-token', 'mock-refresh-token');

    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => ({ id: 'user-1' }),
    });

    await request('/users/me', { requiresAuth: true });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['Authorization']).toBe('Bearer mock-access-token');
  });

  it('does not inject Authorization header when requiresAuth is false', async () => {
    await tokenStorage.saveTokens('mock-access-token', 'mock-refresh-token');

    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: async () => ({ status: 'ok' }),
    });

    await request('/auth/sessions', { requiresAuth: false });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers['Authorization']).toBeUndefined();
  });

  it('performs single-flight token refresh when concurrent requests receive 401', async () => {
    await tokenStorage.saveTokens('old-access-token', 'valid-refresh-token');

    const refreshedTokenPair: TokenPairResponse = {
      tokenType: 'Bearer',
      accessToken: 'new-rotated-access-token',
      accessTokenExpiresAt: '2026-09-20T10:15:00Z',
      refreshToken: 'new-rotated-refresh-token',
      refreshTokenExpiresAt: '2026-10-20T10:00:00Z',
      user: {
        id: '11111111-1111-1111-1111-111111111111',
        email: 'athlete@example.com',
        displayName: 'Athlete One',
        status: 'ACTIVE',
        preferredLocale: 'vi-VN',
        timezone: 'Asia/Ho_Chi_Minh',
        emailVerifiedAt: '2026-09-20T08:00:00Z',
        createdAt: '2026-09-20T08:00:00Z',
        phoneNumber: null,
        roles: ['STUDENT'],
        capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
        settings: {
          weekStartsOn: 1,
          measurementSystem: 'METRIC',
          accessibilityPreferences: {},
          privacyPreferences: {},
        },
      },
    };

    // Simulate 2 parallel calls to /users/me both returning 401
    // Call 1 -> 401
    // Call 2 -> 401
    // Refresh -> 200 (returns new token pair)
    // Retry 1 -> 200
    // Retry 2 -> 200
    fetchMock
      .mockImplementationOnce(async () => ({
        ok: false,
        status: 401,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'UNAUTHENTICATED',
          message: 'Token expired',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-1',
          fieldErrors: [],
        }),
      }))
      .mockImplementationOnce(async () => ({
        ok: false,
        status: 401,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'UNAUTHENTICATED',
          message: 'Token expired',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-2',
          fieldErrors: [],
        }),
      }))
      .mockImplementationOnce(async (url: string) => {
        // Must be the refresh endpoint!
        expect(url).toContain('/auth/token-refreshes');
        return {
          ok: true,
          status: 200,
          headers: { get: () => 'application/json' },
          json: async () => refreshedTokenPair,
        };
      })
      .mockImplementationOnce(async () => ({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ data: 'result-1' }),
      }))
      .mockImplementationOnce(async () => ({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ data: 'result-2' }),
      }));

    // Trigger concurrent requests
    const [res1, res2] = await Promise.all([
      request<{ data: string }>('/users/me'),
      request<{ data: string }>('/users/me'),
    ]);

    expect(res1).toEqual({ data: 'result-1' });
    expect(res2).toEqual({ data: 'result-2' });

    // Refresh was called exactly ONCE (preventing refresh token replay detection failure)
    const refreshCalls = fetchMock.mock.calls.filter(([url]) =>
      url.includes('/auth/token-refreshes')
    );
    expect(refreshCalls).toHaveLength(1);

    // New tokens are stored in secure storage
    const stored = await tokenStorage.getTokens();
    expect(stored?.accessToken).toBe('new-rotated-access-token');
    expect(stored?.refreshToken).toBe('new-rotated-refresh-token');
  });

  it('wipes tokens and triggers session expired when refresh fails', async () => {
    await tokenStorage.saveTokens('expired-access', 'invalid-or-revoked-refresh');
    const onSessionExpired = jest.fn();
    registerSessionExpiredHandler(onSessionExpired);

    fetchMock
      .mockImplementationOnce(async () => ({
        ok: false,
        status: 401,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'UNAUTHENTICATED',
          message: 'Access token expired',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-1',
          fieldErrors: [],
        }),
      }))
      .mockImplementationOnce(async () => ({
        ok: false,
        status: 401,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'INVALID_REFRESH_TOKEN',
          message: 'Refresh token has been revoked',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-2',
          fieldErrors: [],
        }),
      }));

    await expect(request('/users/me')).rejects.toThrow(ApiError);

    // Tokens wiped
    expect(await tokenStorage.getTokens()).toBeNull();
    // Session expired callback invoked
    expect(onSessionExpired).toHaveBeenCalled();
  });

  it('logout sends refresh token to DELETE /auth/sessions and always clears storage', async () => {
    await tokenStorage.saveTokens('valid-access', 'refresh-to-revoke');

    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 204,
      headers: { get: () => 'application/json' },
    });

    await authApi.logout();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('/auth/sessions');
    expect(init.method).toBe('DELETE');
    expect(JSON.parse(init.body)).toEqual({ refreshToken: 'refresh-to-revoke' });
    expect(await tokenStorage.getTokens()).toBeNull();
  });
});
