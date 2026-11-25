import React from 'react';
import { Text, View } from 'react-native';
import renderer from 'react-test-renderer';
import * as SecureStore from 'expo-secure-store';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { queryClient } from '@/services/queryClient';
import { capabilityStorage, tokenStorage } from '@/services/storage';
import { CurrentUserResponse, TokenPairResponse } from '@/types/auth';

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
  const { status, user, activeCapability, refreshUser, login, logout, setActiveCapability } = useAuth();
  onReady({ refreshUser, login, logout, setActiveCapability });
  return (
    <View>
      <Text testID="auth-status">{status}</Text>
      <Text testID="user-id">{user?.id ?? 'NONE'}</Text>
      <Text testID="active-capability">{activeCapability ?? 'NONE'}</Text>
    </View>
  );
}

async function flushMicrotasks(rounds = 10) {
  for (let i = 0; i < rounds; i += 1) {
    await Promise.resolve();
  }
}

describe('Native SecureStore serialization & session ordering (P1 storage race regression)', () => {
  let fetchMock: jest.Mock;
  let handle!: AuthHandle;
  let backingStore: Map<string, string>;
  let pendingCleanup: (() => void)[];

  beforeEach(async () => {
    jest.restoreAllMocks();
    jest.clearAllMocks();
    pendingCleanup = [];
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    queryClient.clear();
    fetchMock = jest.fn();
    globalThis.fetch = fetchMock;

    backingStore = new Map<string, string>();
    jest.spyOn(SecureStore, 'getItemAsync').mockImplementation(async (key: string) => backingStore.get(key) ?? null);
    jest.spyOn(SecureStore, 'setItemAsync').mockImplementation(async (key: string, value: string) => {
      backingStore.set(key, value);
    });
    jest.spyOn(SecureStore, 'deleteItemAsync').mockImplementation(async (key: string) => {
      backingStore.delete(key);
    });
  });

  afterEach(() => {
    for (const release of pendingCleanup) {
      release();
    }
  });

  afterAll(() => {
    jest.restoreAllMocks();
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

  it('1. Native token write A pending -> concurrent logout & login B waits for persistence, then B owns state & matched token pair', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    const accessWriteStarted = deferred<void>();
    const heldAccessWriteA = deferred<void>();
    pendingCleanup.push(() => heldAccessWriteA.resolve(undefined));

    (SecureStore.setItemAsync as jest.Mock).mockImplementation((key: string, value: string) => {
      if (key === 'auth_access_token' && value === 'a-new-access') {
        accessWriteStarted.resolve(undefined);
        return heldAccessWriteA.promise.then(() => {
          backingStore.set(key, value);
        });
      }
      backingStore.set(key, value);
      return Promise.resolve();
    });

    fetchMock
      .mockResolvedValueOnce(jsonResponse(401, { errorCode: 'UNAUTHENTICATED', message: 'Expired' }))
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('a-new-access', 'a-new-refresh', userA)));

    let refreshASettled = false;
    const refreshAPromise = handle.refreshUser().then(
      () => { refreshASettled = true; },
      () => { refreshASettled = true; }
    );

    await accessWriteStarted.promise;

    fetchMock
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));

    let loginBSettled = false;
    let logoutPromise!: Promise<void>;
    let loginBPromise!: Promise<TokenPairResponse>;
    await renderer.act(async () => {
      logoutPromise = handle.logout();
      loginBPromise = handle.login({ email: 'b@example.com', password: 'Password123!' });
      void loginBPromise.finally(() => { loginBSettled = true; });
      await flushMicrotasks(15);
    });

    // While A's native write is still held, B must NOT publish AUTHENTICATED prematurely
    expect(loginBSettled).toBe(false);
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).not.toBe('user-b');

    await renderer.act(async () => {
      heldAccessWriteA.resolve(undefined);
      await Promise.all([logoutPromise, loginBPromise, refreshAPromise]);
    });

    expect(refreshASettled).toBe(true);
    expect(loginBSettled).toBe(true);
    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(await tokenStorage.getTokens()).toEqual({
      accessToken: 'b-access',
      refreshToken: 'b-refresh',
    });
    renderer.act(() => tree.unmount());
  });

  it('2. Old session native deleteItemAsync pending -> new login B waits and is NOT deleted by old clear', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    const deleteStarted = deferred<void>();
    const heldDeleteAccess = deferred<void>();
    pendingCleanup.push(() => heldDeleteAccess.resolve(undefined));

    (SecureStore.deleteItemAsync as jest.Mock).mockImplementation((key: string) => {
      if (key === 'auth_access_token' && backingStore.get(key) === 'a-access') {
        deleteStarted.resolve(undefined);
        return heldDeleteAccess.promise.then(() => {
          backingStore.delete(key);
        });
      }
      backingStore.delete(key);
      return Promise.resolve();
    });

    fetchMock
      .mockResolvedValueOnce(jsonResponse(204, undefined))
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));

    let logoutPromise!: Promise<void>;
    await renderer.act(async () => {
      logoutPromise = handle.logout();
    });
    await deleteStarted.promise;

    let loginBSettled = false;
    let loginBPromise!: Promise<TokenPairResponse>;
    await renderer.act(async () => {
      loginBPromise = handle.login({ email: 'b@example.com', password: 'Password123!' });
      void loginBPromise.finally(() => { loginBSettled = true; });
      await flushMicrotasks(15);
    });

    expect(loginBSettled).toBe(false);

    await renderer.act(async () => {
      heldDeleteAccess.resolve(undefined);
      await Promise.all([logoutPromise, loginBPromise]);
    });

    expect(loginBSettled).toBe(true);
    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(await tokenStorage.getTokens()).toEqual({
      accessToken: 'b-access',
      refreshToken: 'b-refresh',
    });
    renderer.act(() => tree.unmount());
  });

  it('3. Stale native capability write & cleanup pending do NOT overwrite newer capability in SecureStore', async () => {
    const dualUser = makeUser('user-dual', 'Dual', true, true);
    await capabilityStorage.saveActiveCapability('STUDENT');
    const tree = await mountWithUser(dualUser, 'dual-access', 'dual-refresh');

    const capWriteStarted = deferred<void>();
    const heldCapabilityWriteStudent = deferred<void>();
    pendingCleanup.push(() => heldCapabilityWriteStudent.resolve(undefined));

    (SecureStore.setItemAsync as jest.Mock).mockImplementation((key: string, value: string) => {
      if (key === 'active_mobile_capability' && value === 'STUDENT') {
        capWriteStarted.resolve(undefined);
        return heldCapabilityWriteStudent.promise.then(() => {
          backingStore.set(key, value);
        });
      }
      backingStore.set(key, value);
      return Promise.resolve();
    });

    const switchStudentPromise = handle.setActiveCapability('STUDENT');
    await capWriteStarted.promise;

    let switchTrainerSettled = false;
    const switchTrainerPromise = handle.setActiveCapability('TRAINER').finally(() => {
      switchTrainerSettled = true;
    });
    await flushMicrotasks(10);
    expect(switchTrainerSettled).toBe(false);

    await renderer.act(async () => {
      heldCapabilityWriteStudent.resolve(undefined);
      await Promise.all([switchStudentPromise, switchTrainerPromise]);
    });

    expect(tree.root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');
    expect(await capabilityStorage.getActiveCapability()).toBe('TRAINER');

    // Held capability cleanup from old logout never wipes new login's capability
    const capDeleteStarted = deferred<void>();
    const heldCapabilityDelete = deferred<void>();
    pendingCleanup.push(() => heldCapabilityDelete.resolve(undefined));
    (SecureStore.deleteItemAsync as jest.Mock).mockImplementation((key: string) => {
      if (key === 'active_mobile_capability') {
        capDeleteStarted.resolve(undefined);
        return heldCapabilityDelete.promise.then(() => {
          backingStore.delete(key);
        });
      }
      backingStore.delete(key);
      return Promise.resolve();
    });

    const userB = makeUser('user-b', 'Dual B', true, true);
    fetchMock
      .mockResolvedValueOnce(jsonResponse(204, undefined))
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));

    const logoutP = handle.logout();
    await capDeleteStarted.promise;

    let loginBPSettled = false;
    const loginBP = handle.login({ email: 'b@example.com', password: 'Password123!' }).finally(() => {
      loginBPSettled = true;
    });
    await flushMicrotasks(12);
    expect(loginBPSettled).toBe(false);

    await renderer.act(async () => {
      heldCapabilityDelete.resolve(undefined);
      await Promise.all([logoutP, loginBP]);
    });

    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(tree.root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');
    expect(await capabilityStorage.getActiveCapability()).toBe('STUDENT');
    renderer.act(() => tree.unmount());
  });

  it('4. Storage error during saveTokens does NOT wedge the queue or block subsequent valid session login', async () => {
    const userA = makeUser('user-a', 'Account A');
    const userB = makeUser('user-b', 'Account B');
    const tree = await mountWithUser(userA, 'a-access', 'a-refresh');

    (SecureStore.setItemAsync as jest.Mock).mockRejectedValueOnce(new Error('Keystore busy'));
    await expect(tokenStorage.saveTokens('broken-access', 'broken-refresh')).rejects.toThrow('Keystore busy');

    fetchMock
      .mockResolvedValueOnce(jsonResponse(204, undefined))
      .mockResolvedValueOnce(jsonResponse(200, makeTokenPair('b-access', 'b-refresh', userB)));

    await renderer.act(async () => {
      await handle.logout();
      await handle.login({ email: 'b@example.com', password: 'Password123!' });
    });

    expect(tree.root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(tree.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-b');
    expect(await tokenStorage.getTokens()).toEqual({
      accessToken: 'b-access',
      refreshToken: 'b-refresh',
    });
    renderer.act(() => tree.unmount());
  });

  it('5. Concurrent access/refresh writes never produce mixed-session token pairs', async () => {
    const firstAccessStarted = deferred<void>();
    const heldFirstAccess = deferred<void>();
    pendingCleanup.push(() => heldFirstAccess.resolve(undefined));
    let firstWriteDone = false;

    (SecureStore.setItemAsync as jest.Mock).mockImplementation((key: string, value: string) => {
      if (key === 'auth_access_token' && value === 'a-access' && !firstWriteDone) {
        firstWriteDone = true;
        firstAccessStarted.resolve(undefined);
        return heldFirstAccess.promise.then(() => {
          backingStore.set(key, value);
        });
      }
      backingStore.set(key, value);
      return Promise.resolve();
    });

    const saveA = tokenStorage.saveTokens('a-access', 'a-refresh');
    await firstAccessStarted.promise;

    let saveBSettled = false;
    const saveB = tokenStorage.saveTokens('b-access', 'b-refresh').finally(() => {
      saveBSettled = true;
    });
    await flushMicrotasks(8);
    expect(saveBSettled).toBe(false);

    heldFirstAccess.resolve(undefined);
    await Promise.all([saveA, saveB]);

    const finalTokens = await tokenStorage.getTokens();
    expect(finalTokens).toEqual({
      accessToken: 'b-access',
      refreshToken: 'b-refresh',
    });
  });

  it.each([
    { failingKey: 'auth_access_token', pendingKey: 'auth_refresh_token', errorMessage: 'access delete failed' },
    { failingKey: 'auth_refresh_token', pendingKey: 'auth_access_token', errorMessage: 'refresh delete failed' },
  ] as const)(
    '6. clearTokens waits for sibling pending delete when $failingKey rejects before releasing queue to save B',
    async ({ failingKey, pendingKey, errorMessage }) => {
      await tokenStorage.saveTokens('a-access', 'a-refresh');

      const pendingDeleteStarted = deferred<void>();
      const heldSiblingDelete = deferred<void>();
      pendingCleanup.push(() => heldSiblingDelete.resolve(undefined));

      let saveBStarted = false;
      let saveBSettled = false;

      (SecureStore.deleteItemAsync as jest.Mock).mockImplementation((key: string) => {
        if (key === failingKey) {
          return Promise.reject(new Error(errorMessage));
        }
        if (key === pendingKey) {
          pendingDeleteStarted.resolve(undefined);
          return heldSiblingDelete.promise.then(() => {
            backingStore.delete(key);
          });
        }
        backingStore.delete(key);
        return Promise.resolve();
      });

      (SecureStore.setItemAsync as jest.Mock).mockImplementation((key: string, value: string) => {
        if (value === 'b-access' || value === 'b-refresh') {
          saveBStarted = true;
        }
        backingStore.set(key, value);
        return Promise.resolve();
      });

      let clearError: unknown = null;
      let clearSettled = false;
      const clearPromise = tokenStorage.clearTokens().then(
        () => { clearSettled = true; },
        (err) => {
          clearError = err;
          clearSettled = true;
        }
      );

      await pendingDeleteStarted.promise;

      const saveBPromise = tokenStorage.saveTokens('b-access', 'b-refresh').finally(() => {
        saveBSettled = true;
      });
      await flushMicrotasks(12);

      // Queue must NOT advance to save B (or settle clear A) while sibling delete A is still in flight.
      expect(clearSettled).toBe(false);
      expect(saveBStarted).toBe(false);
      expect(saveBSettled).toBe(false);

      heldSiblingDelete.resolve(undefined);
      await Promise.all([clearPromise, saveBPromise]);

      expect(clearSettled).toBe(true);
      expect(clearError).toBeInstanceOf(Error);
      expect((clearError as Error).message).toBe(errorMessage);
      expect(saveBStarted).toBe(true);
      expect(saveBSettled).toBe(true);
      expect(await tokenStorage.getTokens()).toEqual({
        accessToken: 'b-access',
        refreshToken: 'b-refresh',
      });
    }
  );
});
