import React from 'react';
import renderer from 'react-test-renderer';
import Index from '@/app/index';
import { useAuth } from '@/features/auth/AuthContext';
import { CurrentUserResponse } from '@/types/auth';

const mockRedirect = jest.fn();
jest.mock('expo-router', () => ({
  Redirect: (props: { href: string }) => {
    mockRedirect(props.href);
    return null;
  },
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

describe('Session Restoration Navigation Resolution in Index', () => {
  const createMockUser = (hasStudentProfile: boolean): CurrentUserResponse => ({
    id: 'restored-user-id',
    email: 'restored@example.com',
    displayName: 'Restored User',
    status: 'ACTIVE',
    preferredLocale: 'vi-VN',
    timezone: 'Asia/Ho_Chi_Minh',
    emailVerifiedAt: '2026-09-20T08:00:00Z',
    createdAt: '2026-09-20T08:00:00Z',
    phoneNumber: null,
    roles: hasStudentProfile ? ['STUDENT'] : [],
    capabilities: {
      hasStudentProfile,
      hasTrainerProfile: false,
      canCoach: false,
    },
    settings: {
      weekStartsOn: 1,
      measurementSystem: 'METRIC',
      accessibilityPreferences: {},
      privacyPreferences: {},
    },
  });

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders nothing while session is initializing / hydrating', () => {
    (useAuth as jest.Mock).mockReturnValue({
      isLoading: true,
      status: 'INITIALIZING',
      user: null,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<Index />);
    });

    expect(tree!.toJSON()).toBeNull();
    expect(mockRedirect).not.toHaveBeenCalled();
  });

  it('resolves and redirects to /(onboarding)/student after session restoration when authenticated user hasStudentProfile is false', () => {
    (useAuth as jest.Mock).mockReturnValue({
      isLoading: false,
      status: 'AUTHENTICATED',
      user: createMockUser(false),
    });

    renderer.act(() => {
      renderer.create(<Index />);
    });

    expect(mockRedirect).toHaveBeenCalledWith('/(onboarding)/student');
  });

  it('resolves and redirects to /(app) after session restoration when authenticated user hasStudentProfile is true', () => {
    (useAuth as jest.Mock).mockReturnValue({
      isLoading: false,
      status: 'AUTHENTICATED',
      user: createMockUser(true),
    });

    renderer.act(() => {
      renderer.create(<Index />);
    });

    expect(mockRedirect).toHaveBeenCalledWith('/(app)');
  });

  it('redirects to /(auth)/sign-in when unauthenticated', () => {
    (useAuth as jest.Mock).mockReturnValue({
      isLoading: false,
      status: 'UNAUTHENTICATED',
      user: null,
    });

    renderer.act(() => {
      renderer.create(<Index />);
    });

    expect(mockRedirect).toHaveBeenCalledWith('/(auth)/sign-in');
  });
});
