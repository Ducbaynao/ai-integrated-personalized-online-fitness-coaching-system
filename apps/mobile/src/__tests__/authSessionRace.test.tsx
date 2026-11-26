import React from 'react';
import { Pressable, Text, View } from 'react-native';
import renderer from 'react-test-renderer';
import * as SecureStore from 'expo-secure-store';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { authApi, request } from '@/services/apiClient';
import { queryClient } from '@/services/queryClient';
import { capabilityStorage, tokenStorage } from '@/services/storage';
import {
  clearPendingCoachingQrIntent,
  getPendingCoachingQrIntent,
  setPendingCoachingQrIntent,
} from '@/features/coaching/pendingQrIntent';
import { ApiError, CurrentUserResponse, TokenPairResponse } from '@/types/auth';

jest.mock('@/services/pushApi', () => ({
  pushApi: {
    unregister: jest.fn().mockResolvedValue(undefined),
  },
}));

const originalFetch = globalThis.fetch;

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function makeUser(
  id: string,
  displayName: string,
  hasStudentProfile = true,
  hasTrainerProfile = false
): CurrentUserResponse {
  return {
    id,
    email: `${id}@example.com`,
    displayName,
    status: 'ACTIVE',
    preferredLocale: 'vi-VN',
    timezone: 'Asia/Ho_Chi_Minh',
    emailVerifiedAt: '2026-09-20T08:00:00Z',
    createdAt: '2026-09-20T08:00:00Z',
    phoneNumber: null,
    roles: [
      ...(hasStudentProfile ? ['STUDENT' as const] : []),
      ...(hasTrainerProfile ? ['TRAINER' as const] : []),
    ],
    permissions: [],
    capabilities: {
      hasStudentProfile,
      hasTrainerProfile,
      canCoach: false,
    },
    settings: {
      weekStartsOn: 1,
      measurementSystem: 'METRIC',
      accessibilityPreferences: {},
      privacyPreferences: {},
    },
  };
}

function makeTokenPair(accessToken: string, refreshToken: string, user: CurrentUserResponse): TokenPairResponse {
  return {
    tokenType: 'Bearer',
    accessToken,
    accessTokenExpiresAt: '2026-11-25T10:15:00Z',
    refreshToken,
    refreshTokenExpiresAt: '2026-12-25T10:00:00Z',
    user,
  };
}

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
  };
}

interface AuthHandle {
  refreshUser: ReturnType<typeof useAuth>['refreshUser'];
  login: ReturnType<typeof useAuth>['login'];
  logout: ReturnType<typeof useAuth>['logout'];
  setActiveCapability: ReturnType<typeof useAuth>['setActiveCapability'];
}

function Harness({ onReady }: { onReady: (handle: AuthHandle) => void }) {
  const { status, user, activeCapability, sessionNotice, refreshUser, login, logout, setActiveCapability } = useAuth();
  onReady({ refreshUser, login, logout, setActiveCapability });
  return (
    <View>
      <Text testID="auth-status">{status}</Text>
      <Text testID="user-id">{user?.id ?? 'NONE'}</Text>
      <Text testID="display-name">{user?.displayName ?? 'NONE'}</Text>
      <Text testID="has-trainer">{String(user?.capabilities?.hasTrainerProfile ?? false)}</Text>
      <Text testID="active-capability">{activeCapability ?? 'NONE'}</Text>
      <Text testID="session-notice">{sessionNotice ?? 'NONE'}</Text>
      <Pressable testID="noop" onPress={() => {}} />
    </View>
  );
}

describe('AuthProvider & apiClient session-epoch race protection (P1 regression)', () => {
  let fetchMock: jest.Mock;
  let handle!: AuthHandle;

  beforeEach(async () => {
    jest.clearAllMocks();
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    queryClient.clear();
    fetchMock = jest.fn();
    globalThis.fetch = fetchMock;
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  async function mountWithUser(initialUser: CurrentUserResponse, accessToken: string, refreshToken: string) {
    await tokenStorage.saveTokens(accessToken, refreshToken);
    fetchMock.mockResolvedValueOnce(jsonResponse(200, initialUser));
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <Harness onReady={(h) => { handle = h; }} />
        </AuthProvider>
      );
    });
    return tree;
  }

  it('1. P1 reproduction: A refresh pending -> logout -> B login -> stale A refresh success leaves B, tokens, and cache intact', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    const pendingA = deferred<ReturnType<typeof jsonResponse>>();
    fetchMock.mockImplementationOnce(() => pendingA.promise); // GET /users/me for A

    let refreshAPromise!: Promise<CurrentUserResponse>;
    await renderer.act(async () => {
      refreshAPromise = handle.refreshUser();
    });

    // Logout A and login B while A's /users/me is still in flight
    fetchMock.mockResolvedValueOnce(jsonResponse(204, undefined)); // DELETE /auth/sessions
    await renderer.act(async () => {
      await handle.logout();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB))); // POST /auth/sessions
    await renderer.act(async () => {
      await handle.login({ email: 'b@example.com', password: 'Password123!' });
    });

    queryClient.setQueryData(['private-b'], 'b-only-data');
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');

    // Late resolution of stale A refresh
    let refreshSettledWithError = false;
    await renderer.act(async () => {
      pendingA.resolve(jsonResponse(200, userA));
      try {
        await refreshAPromise;
      } catch (err) {
        expect(err).toBeInstanceOf(ApiError);
        refreshSettledWithError = true;
      }
    });

    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(tree.root.findByProps({ testID: 'display-name' }).props.children).toBe('Account B');
    expect(await tokenStorage.getTokens()).toEqual({ accessToken: 'b-access', refreshToken: 'b-refresh' });
    expect(queryClient.getQueryData(['private-b'])).toBe('b-only-data');
    expect(refreshSettledWithError).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('2. P1 interceptor failure: A 401 -> token refresh pending -> logout -> B login -> stale A token refresh 401 does NOT wipe B session, tokens, or cache', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    const pendingTokenRefresh = deferred<ReturnType<typeof jsonResponse>>();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' })) // GET /users/me -> 401
      .mockImplementationOnce(() => pendingTokenRefresh.promise); // POST /auth/token-refreshes held

    let refreshAPromise!: Promise<CurrentUserResponse>;
    await renderer.act(async () => {
      refreshAPromise = handle.refreshUser();
      await Promise.resolve();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(204, undefined)); // DELETE /auth/sessions
    await renderer.act(async () => {
      await handle.logout();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));
    await renderer.act(async () => {
      await handle.login({ email: 'b@example.com', password: 'Password123!' });
    });
    queryClient.setQueryData(['private-b'], 'b-secret');

    // Old A token refresh fails 401
    await renderer.act(async () => {
      pendingTokenRefresh.resolve(jsonResponse(401, { errorCode: 'INVALID_REFRESH_TOKEN', message: 'Revoked' }));
      try {
        await refreshAPromise;
      } catch {
        // expected rejection for stale A
      }
    });

    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(tree.root.findByProps({ testID: 'session-notice' }).props.children).toBe('NONE');
    expect(await tokenStorage.getTokens()).toEqual({ accessToken: 'b-access', refreshToken: 'b-refresh' });
    expect(queryClient.getQueryData(['private-b'])).toBe('b-secret');
    renderer.act(() => tree.unmount());
  });

  it('3. P1 interceptor success: A 401 -> token refresh pending -> logout -> B login -> stale A token refresh 200 does NOT overwrite B tokens in storage', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    const pendingTokenRefresh = deferred<ReturnType<typeof jsonResponse>>();
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' }))
      .mockImplementationOnce(() => pendingTokenRefresh.promise);

    let refreshAPromise!: Promise<CurrentUserResponse>;
    await renderer.act(async () => {
      refreshAPromise = handle.refreshUser();
      await Promise.resolve();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(204, undefined));
    await renderer.act(async () => {
      await handle.logout();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));
    await renderer.act(async () => {
      await handle.login({ email: 'b@example.com', password: 'Password123!' });
    });

    await renderer.act(async () => {
      pendingTokenRefresh.resolve(jsonResponse(200, makeTokenPair('a-new-access', 'a-new-refresh', userA)));
      try {
        await refreshAPromise;
      } catch {
        // expected rejection for stale A
      }
    });

    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(await tokenStorage.getTokens()).toEqual({ accessToken: 'b-access', refreshToken: 'b-refresh' });
    renderer.act(() => tree.unmount());
  });

  it('4. Same account re-login (A -> logout -> A): stale A refresh does NOT overwrite new A session state, tokens, or cache', async () => {
    const oldSessionUser = makeUser('user-a', 'Old A Snapshot');
    const freshSessionUser = makeUser('user-a', 'Fresh A Session');
    const tree = await mountWithUser(oldSessionUser, 'a-old-access', 'a-old-refresh');

    const pendingOld = deferred<ReturnType<typeof jsonResponse>>();
    fetchMock.mockImplementationOnce(() => pendingOld.promise);

    let oldRefreshPromise!: Promise<CurrentUserResponse>;
    await renderer.act(async () => {
      oldRefreshPromise = handle.refreshUser();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(204, undefined));
    await renderer.act(async () => {
      await handle.logout();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('a-new-access', 'a-new-refresh', freshSessionUser)));
    await renderer.act(async () => {
      await handle.login({ email: 'a@example.com', password: 'Password123!' });
    });
    queryClient.setQueryData(['a-fresh-session'], 'fresh-value');

    let rejected = false;
    await renderer.act(async () => {
      pendingOld.resolve(jsonResponse(200, oldSessionUser));
      try {
        await oldRefreshPromise;
      } catch {
        rejected = true;
      }
    });

    expect(tree.root.findByProps({ testID: 'display-name' }).props.children).toBe('Fresh A Session');
    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(await tokenStorage.getTokens()).toEqual({ accessToken: 'a-new-access', refreshToken: 'a-new-refresh' });
    expect(queryClient.getQueryData(['a-fresh-session'])).toBe('fresh-value');
    expect(rejected).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('5. Capability change while refresh pending: stale refreshUser does NOT revert capability or restore stale capability snapshot', async () => {
    const dualUserBefore = makeUser('user-dual', 'Dual', true, true);
    const staleSingleUser = makeUser('user-dual', 'Dual Stale', true, false);
    await capabilityStorage.saveActiveCapability('STUDENT');
    const tree = await mountWithUser(dualUserBefore, 'dual-access', 'dual-refresh');

    const pendingRefresh = deferred<ReturnType<typeof jsonResponse>>();
    fetchMock.mockImplementationOnce(() => pendingRefresh.promise);

    let staleRefreshPromise!: Promise<CurrentUserResponse>;
    await renderer.act(async () => {
      staleRefreshPromise = handle.refreshUser('STUDENT');
    });

    await renderer.act(async () => {
      await handle.setActiveCapability('TRAINER');
    });
    expect(tree.root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');
    expect(await capabilityStorage.getActiveCapability()).toBe('TRAINER');

    let rejected = false;
    await renderer.act(async () => {
      pendingRefresh.resolve(jsonResponse(200, staleSingleUser));
      try {
        await staleRefreshPromise;
      } catch {
        rejected = true;
      }
    });

    expect(tree.root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');
    expect(tree.root.findByProps({ testID: 'has-trainer' }).props.children).toBe('true');
    expect(await capabilityStorage.getActiveCapability()).toBe('TRAINER');
    expect(rejected).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('6. Same valid session: expired access token refreshes via single-flight POST /auth/token-refreshes and updates user + tokens', async () => {
    const userA = makeUser('user-a', 'Before Refresh');
    const userARefreshed = makeUser('user-a', 'After Refresh');
    const tree = await mountWithUser(userA, 'expired-access', 'valid-refresh');

    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' }))
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('fresh-access', 'fresh-refresh', userARefreshed)))
      .mockResolvedValueOnce(jsonResponse(200, userARefreshed));

    let returnedUser!: CurrentUserResponse;
    await renderer.act(async () => {
      returnedUser = await handle.refreshUser();
    });

    expect(returnedUser.displayName).toBe('After Refresh');
    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(tree.root.findByProps({ testID: 'display-name' }).props.children).toBe('After Refresh');
    expect(await tokenStorage.getTokens()).toEqual({ accessToken: 'fresh-access', refreshToken: 'fresh-refresh' });

    // Confirm authApi/request still supports concurrent single-flight refresh cleanly
    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' }))
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' }))
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('fresh-access-2', 'fresh-refresh-2', userARefreshed)))
      .mockResolvedValueOnce(jsonResponse(200, { ok: 1 }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: 2 }));
    const [r1, r2] = await Promise.all([
      request<{ ok: number }>('/users/me'),
      authApi.getCurrentUser(),
    ]);
    expect(r1).toEqual({ ok: 1 });
    expect(r2).toEqual({ ok: 2 });
    expect(await tokenStorage.getTokens()).toEqual({ accessToken: 'fresh-access-2', refreshToken: 'fresh-refresh-2' });
    renderer.act(() => tree.unmount());
  });

  it('7. Logout and session expiration clear pendingQrIntent, while stale logout does NOT clear a newer session intent', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    setPendingCoachingQrIntent({ publicCode: 'abc1234567', payloadVersion: 1 });
    expect(getPendingCoachingQrIntent()).toEqual({ publicCode: 'abc1234567', payloadVersion: 1 });

    fetchMock.mockResolvedValueOnce(jsonResponse(204, undefined));
    await renderer.act(async () => {
      await handle.logout();
    });
    expect(getPendingCoachingQrIntent()).toBeNull();

    // Re-login A, set pendingQrIntent, then expire session via 401 -> 401
    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('a-access-2', 'a-refresh-2', userA)));
    await renderer.act(async () => {
      await handle.login({ email: 'a@example.com', password: 'Password123!' });
    });
    setPendingCoachingQrIntent({ publicCode: 'expired789', payloadVersion: 1 });

    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' }))
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Refresh failed' }));

    await renderer.act(async () => {
      try {
        await handle.refreshUser();
      } catch {
        // expected session expiration
      }
    });
    expect(getPendingCoachingQrIntent()).toBeNull();

    // Race: logout A is still awaiting remote /auth/sessions DELETE while B logs in and sets new QR intent
    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('a-access-3', 'a-refresh-3', userA)));
    await renderer.act(async () => {
      await handle.login({ email: 'a@example.com', password: 'Password123!' });
    });

    const pendingLogoutDelete = deferred<ReturnType<typeof jsonResponse>>();
    fetchMock.mockImplementationOnce(() => pendingLogoutDelete.promise);

    let logoutPromise!: Promise<void>;
    await renderer.act(async () => {
      logoutPromise = handle.logout();
      await Promise.resolve();
    });

    fetchMock.mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));
    await renderer.act(async () => {
      await handle.login({ email: 'b@example.com', password: 'Password123!' });
    });
    setPendingCoachingQrIntent({ publicCode: 'freshB1234', payloadVersion: 1 });

    await renderer.act(async () => {
      pendingLogoutDelete.resolve(jsonResponse(204, undefined));
      await logoutPromise;
    });

    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(getPendingCoachingQrIntent()).toEqual({ publicCode: 'freshB1234', payloadVersion: 1 });
    clearPendingCoachingQrIntent();
    renderer.act(() => tree.unmount());
  });

  it('8. setActiveCapability removes QR query cache cleanly and updates capability', async () => {
    const dualUser = makeUser('user-dual', 'Dual', true, true);
    await capabilityStorage.saveActiveCapability('STUDENT');
    const tree = await mountWithUser(dualUser, 'dual-access', 'dual-refresh');

    queryClient.setQueryData(['coaching', 'qr', 'me', 'user-dual', 'STUDENT'], { publicCode: 'student-code' });
    await renderer.act(async () => {
      await handle.setActiveCapability('TRAINER');
    });

    expect(tree.root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');
    expect(queryClient.getQueryData(['coaching', 'qr', 'me', 'user-dual', 'STUDENT'])).toBeUndefined();
    renderer.act(() => tree.unmount());
  });
});
