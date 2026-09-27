import React from 'react';
import { Pressable, Text, View } from 'react-native';
import renderer from 'react-test-renderer';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { authApi } from '@/services/apiClient';
import { capabilityStorage, tokenStorage } from '@/services/storage';
import { ApiError, CurrentUserResponse } from '@/types/auth';
import * as SecureStore from 'expo-secure-store';

jest.mock('@/services/apiClient', () => ({
  authApi: {
    getCurrentUser: jest.fn(),
    login: jest.fn(),
    logout: jest.fn(),
    register: jest.fn(),
    confirmEmail: jest.fn(),
  },
  registerSessionExpiredHandler: jest.fn(),
}));

function TestConsumer() {
  const {
    status,
    user,
    activeCapability,
    restoreError,
    sessionNotice,
    refreshUser,
    setActiveCapability,
    retrySessionRestore,
    login,
  } = useAuth();
  return (
    <View testID="test-consumer">
      <Text testID="auth-status">{status}</Text>
      <Text testID="active-capability">{activeCapability ?? 'NONE'}</Text>
      <Text testID="restore-error">{restoreError ?? 'NONE'}</Text>
      <Text testID="session-notice">{sessionNotice ?? 'NONE'}</Text>
      <Text testID="has-student">{String(user?.capabilities?.hasStudentProfile)}</Text>
      <Text testID="has-trainer">{String(user?.capabilities?.hasTrainerProfile)}</Text>
      <Pressable testID="refresh-as-trainer" onPress={() => refreshUser('TRAINER')} />
      <Pressable testID="refresh-as-student" onPress={() => refreshUser('STUDENT')} />
      <Pressable testID="switch-to-trainer" onPress={() => setActiveCapability('TRAINER')} />
      <Pressable testID="switch-to-student" onPress={() => setActiveCapability('STUDENT')} />
      <Pressable testID="retry-session" onPress={() => retrySessionRestore()} />
      <Pressable
        testID="login-dual-role"
        onPress={() => login({ email: 'integration@example.com', password: 'Password123!' })}
      />
    </View>
  );
}

describe('AuthProvider Capabilities Integration Tests (Finding 1 & 2)', () => {
  const createMockUser = (
    hasStudentProfile: boolean,
    hasTrainerProfile: boolean
  ): CurrentUserResponse => ({
    id: 'user-integration-1',
    email: 'integration@example.com',
    displayName: 'Integration User',
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
  });

  beforeEach(async () => {
    jest.clearAllMocks();
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    await tokenStorage.saveTokens('valid-access-token', 'valid-refresh-token');
  });

  it('1. Hydrates dual-role user with persisted STUDENT capability', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    await capabilityStorage.saveActiveCapability('STUDENT');

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');
  });

  it('2. Hydrates dual-role user with persisted TRAINER capability', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    await capabilityStorage.saveActiveCapability('TRAINER');

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');
  });

  it('3. Hydrates dual-role user with invalid persisted capability and falls back safely to STUDENT', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    await capabilityStorage.saveActiveCapability('UNKNOWN_VALUE');

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Storage is reconciled to STUDENT
    const persisted = await capabilityStorage.getActiveCapability();
    expect(persisted).toBe('STUDENT');
  });

  it('4. Cross-activation: Student-only -> creates Trainer -> refreshUser(TRAINER) activates and persists TRAINER', async () => {
    // Initial hydration: Student-only
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, false));

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // User creates Trainer Profile; subsequent getCurrentUser returns dual-role
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));

    // Call refreshUser('TRAINER')
    await renderer.act(async () => {
      root.findByProps({ testID: 'refresh-as-trainer' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'has-trainer' }).props.children).toBe('true');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');

    const persisted = await capabilityStorage.getActiveCapability();
    expect(persisted).toBe('TRAINER');
  });

  it('5. Cross-activation: Trainer-only -> creates Student -> refreshUser(STUDENT) activates and persists STUDENT', async () => {
    // Initial hydration: Trainer-only
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(false, true));

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');

    // User creates Student Profile; subsequent getCurrentUser returns dual-role
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));

    // Call refreshUser('STUDENT')
    await renderer.act(async () => {
      root.findByProps({ testID: 'refresh-as-student' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'has-student' }).props.children).toBe('true');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    const persisted = await capabilityStorage.getActiveCapability();
    expect(persisted).toBe('STUDENT');
  });

  it('6. Capability switching does not clear or mutate authentication tokens in storage', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    await capabilityStorage.saveActiveCapability('STUDENT');

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Switch to TRAINER
    await renderer.act(async () => {
      root.findByProps({ testID: 'switch-to-trainer' }).props.onPress();
    });
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');

    // Tokens must remain untouched
    let tokens = await tokenStorage.getTokens();
    expect(tokens).toEqual({
      accessToken: 'valid-access-token',
      refreshToken: 'valid-refresh-token',
    });

    // Switch to STUDENT
    await renderer.act(async () => {
      root.findByProps({ testID: 'switch-to-student' }).props.onPress();
    });
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Tokens still untouched
    tokens = await tokenStorage.getTokens();
    expect(tokens).toEqual({
      accessToken: 'valid-access-token',
      refreshToken: 'valid-refresh-token',
    });
  });

  it('7. Hydration succeeds even when getActiveCapability rejects; tokens remain intact', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    jest
      .spyOn(capabilityStorage, 'getActiveCapability')
      .mockRejectedValueOnce(new Error('SecureStore read error'));

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    // Status must still be AUTHENTICATED
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    // Fallback capability resolves to STUDENT
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Tokens must NOT be cleared because capability storage error is decoupled
    const tokens = await tokenStorage.getTokens();
    expect(tokens).toEqual({
      accessToken: 'valid-access-token',
      refreshToken: 'valid-refresh-token',
    });
  });

  it('8. Login succeeds even when saveActiveCapability rejects; tokens remain intact', async () => {
    await tokenStorage.clearTokens();
    const clearTokensSpy = jest.spyOn(tokenStorage, 'clearTokens');

    // authApi.login in production is responsible for saving tokens upon successful response
    (authApi.login as jest.Mock).mockImplementationOnce(async () => {
      await tokenStorage.saveTokens('login-access-token', 'login-refresh-token');
      return {
        accessToken: 'login-access-token',
        refreshToken: 'login-refresh-token',
        user: createMockUser(true, true),
      };
    });

    // Capability storage persistence fails
    jest
      .spyOn(capabilityStorage, 'saveActiveCapability')
      .mockRejectedValueOnce(new Error('SecureStore write error'));

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('UNAUTHENTICATED');

    await renderer.act(async () => {
      root.findByProps({ testID: 'login-dual-role' }).props.onPress();
    });

    // Verification: failure in capability persistence must NOT call clearTokens
    expect(clearTokensSpy).not.toHaveBeenCalled();

    // User status and capability fallback correctly
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Tokens saved by login remain intact and untouched
    const tokens = await tokenStorage.getTokens();
    expect(tokens).toEqual({
      accessToken: 'login-access-token',
      refreshToken: 'login-refresh-token',
    });
  });

  it('9. Cross-activation refreshUser(preferred) succeeds when persistence rejects; tokens remain intact', async () => {
    // Initial hydration: Student-only
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, false));

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Subsequent call returns dual-role, but saving persistence throws
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    jest
      .spyOn(capabilityStorage, 'saveActiveCapability')
      .mockRejectedValueOnce(new Error('SecureStore disk full'));

    await renderer.act(async () => {
      root.findByProps({ testID: 'refresh-as-trainer' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'has-trainer' }).props.children).toBe('true');
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');

    const tokens = await tokenStorage.getTokens();
    expect(tokens).toEqual({
      accessToken: 'valid-access-token',
      refreshToken: 'valid-refresh-token',
    });
  });

  it('10. Capability switch still updates state when persistence rejects; tokens remain intact', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(createMockUser(true, true));
    await capabilityStorage.saveActiveCapability('STUDENT');

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('STUDENT');

    // Switch to TRAINER with save rejection
    jest
      .spyOn(capabilityStorage, 'saveActiveCapability')
      .mockRejectedValueOnce(new Error('SecureStore hardware error'));

    await renderer.act(async () => {
      root.findByProps({ testID: 'switch-to-trainer' }).props.onPress();
    });

    // UI state must STILL update to TRAINER
    expect(root.findByProps({ testID: 'active-capability' }).props.children).toBe('TRAINER');

    // Tokens must remain untouched
    const tokens = await tokenStorage.getTokens();
    expect(tokens).toEqual({
      accessToken: 'valid-access-token',
      refreshToken: 'valid-refresh-token',
    });
  });

  it('11. Keeps credentials and supports retry when session restoration fails temporarily', async () => {
    (authApi.getCurrentUser as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'Network unavailable'))
      .mockResolvedValueOnce(createMockUser(true, false));

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('RESTORE_FAILED');
    expect(root.findByProps({ testID: 'restore-error' }).props.children).not.toBe('NONE');
    expect(await tokenStorage.getTokens()).toEqual({
      accessToken: 'valid-access-token',
      refreshToken: 'valid-refresh-token',
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'retry-session' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('AUTHENTICATED');
    expect(root.findByProps({ testID: 'restore-error' }).props.children).toBe('NONE');
  });

  it('12. Clears credentials and shows a sign-in notice for a terminal restoration failure', async () => {
    (authApi.getCurrentUser as jest.Mock).mockRejectedValueOnce(
      new ApiError(401, 'Session revoked')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <TestConsumer />
        </AuthProvider>
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'auth-status' }).props.children).toBe('UNAUTHENTICATED');
    expect(root.findByProps({ testID: 'session-notice' }).props.children).not.toBe('NONE');
    expect(await tokenStorage.getTokens()).toBeNull();
  });
});
