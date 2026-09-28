import React from 'react';
import { Pressable, Text } from 'react-native';
import renderer from 'react-test-renderer';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { authApi, registerSessionExpiredHandler } from '@/services/apiClient';
import { queryClient } from '@/services/queryClient';
import { tokenStorage } from '@/services/storage';
import { CurrentUserResponse } from '@/types/auth';

let sessionExpiredHandler: (() => void) | null = null;

jest.mock('@/services/apiClient', () => ({
  authApi: {
    getCurrentUser: jest.fn(),
    login: jest.fn(),
    logout: jest.fn(),
    register: jest.fn(),
    confirmEmail: jest.fn(),
  },
  registerSessionExpiredHandler: jest.fn((handler) => {
    sessionExpiredHandler = handler;
  }),
}));

const user = (id: string): CurrentUserResponse => ({
  id,
  email: `${id}@example.com`,
  displayName: id,
  status: 'ACTIVE',
  preferredLocale: 'vi-VN',
  timezone: 'Asia/Ho_Chi_Minh',
  emailVerifiedAt: '2026-09-28T00:00:00Z',
  createdAt: '2026-09-28T00:00:00Z',
  phoneNumber: null,
  roles: ['STUDENT'],
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
  settings: {
    weekStartsOn: 1,
    measurementSystem: 'METRIC',
    accessibilityPreferences: {},
    privacyPreferences: {},
  },
});

function Consumer() {
  const { user: currentUser, logout, refreshUser } = useAuth();
  return (
    <>
      <Text testID="user-id">{currentUser?.id ?? 'NONE'}</Text>
      <Pressable testID="logout" onPress={() => logout()} />
      <Pressable testID="refresh" onPress={() => refreshUser()} />
    </>
  );
}

describe('authenticated query cache lifecycle', () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    sessionExpiredHandler = null;
    queryClient.clear();
    await tokenStorage.saveTokens('access', 'refresh');
    (authApi.logout as jest.Mock).mockResolvedValue(undefined);
  });

  it('clears server state on logout', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(user('user-1'));
    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<AuthProvider><Consumer /></AuthProvider>);
    });
    queryClient.setQueryData(['exercise-catalog'], { private: true });

    await renderer.act(async () => tree!.root.findByProps({ testID: 'logout' }).props.onPress());
    expect(queryClient.getQueryData(['exercise-catalog'])).toBeUndefined();
  });

  it('clears server state when the authenticated identity changes', async () => {
    (authApi.getCurrentUser as jest.Mock)
      .mockResolvedValueOnce(user('user-1'))
      .mockResolvedValueOnce(user('user-2'));
    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<AuthProvider><Consumer /></AuthProvider>);
    });
    queryClient.setQueryData(['exercise-catalog'], { private: true });

    await renderer.act(async () => tree!.root.findByProps({ testID: 'refresh' }).props.onPress());
    expect(tree!.root.findByProps({ testID: 'user-id' }).props.children).toBe('user-2');
    expect(queryClient.getQueryData(['exercise-catalog'])).toBeUndefined();
  });

  it('clears server state on forced logout', async () => {
    (authApi.getCurrentUser as jest.Mock).mockResolvedValueOnce(user('user-1'));
    await renderer.act(async () => {
      renderer.create(<AuthProvider><Consumer /></AuthProvider>);
    });
    queryClient.setQueryData(['exercise-catalog'], { private: true });

    expect(registerSessionExpiredHandler).toHaveBeenCalled();
    renderer.act(() => sessionExpiredHandler?.());
    expect(queryClient.getQueryData(['exercise-catalog'])).toBeUndefined();
  });
});

