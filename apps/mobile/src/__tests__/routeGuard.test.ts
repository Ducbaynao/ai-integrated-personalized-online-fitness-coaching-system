import {
  canAccessMainApp,
  getAuthenticatedHomeRoute,
  requiresStudentOnboarding,
} from '@/features/auth/routeGuard';
import { CurrentUserResponse } from '@/types/auth';

describe('Route Guard & Navigation Resolution', () => {
  const createUser = (hasStudentProfile: boolean): CurrentUserResponse => ({
    id: 'test-user-id',
    email: 'user@example.com',
    displayName: 'Test User',
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

  describe('getAuthenticatedHomeRoute', () => {
    it('redirects to onboarding when user does not have student profile', () => {
      const user = createUser(false);
      expect(getAuthenticatedHomeRoute(user)).toBe('/(onboarding)/student');
    });

    it('redirects to main app when user already has student profile', () => {
      const user = createUser(true);
      expect(getAuthenticatedHomeRoute(user)).toBe('/(app)');
    });

    it('falls back to main app if user is null (layout handles unauthenticated)', () => {
      expect(getAuthenticatedHomeRoute(null)).toBe('/(app)');
    });
  });

  describe('canAccessMainApp', () => {
    it('returns false for null user', () => {
      expect(canAccessMainApp(null)).toBe(false);
    });

    it('returns false when hasStudentProfile is false', () => {
      expect(canAccessMainApp(createUser(false))).toBe(false);
    });

    it('returns true when hasStudentProfile is true', () => {
      expect(canAccessMainApp(createUser(true))).toBe(true);
    });
  });

  describe('requiresStudentOnboarding', () => {
    it('returns false for null user', () => {
      expect(requiresStudentOnboarding(null)).toBe(false);
    });

    it('returns true when hasStudentProfile is false', () => {
      expect(requiresStudentOnboarding(createUser(false))).toBe(true);
    });

    it('returns false when hasStudentProfile is true', () => {
      expect(requiresStudentOnboarding(createUser(true))).toBe(false);
    });
  });

  describe('Session Restoration Navigation Resolution', () => {
    it('resolves to onboarding when restored session has authenticated user without student profile (hasStudentProfile: false)', () => {
      const restoredUser = createUser(false);
      expect(getAuthenticatedHomeRoute(restoredUser)).toBe('/(onboarding)/student');
      expect(requiresStudentOnboarding(restoredUser)).toBe(true);
      expect(canAccessMainApp(restoredUser)).toBe(false);
    });

    it('resolves to main app when restored session has authenticated user with student profile (hasStudentProfile: true)', () => {
      const restoredUser = createUser(true);
      expect(getAuthenticatedHomeRoute(restoredUser)).toBe('/(app)');
      expect(requiresStudentOnboarding(restoredUser)).toBe(false);
      expect(canAccessMainApp(restoredUser)).toBe(true);
    });
  });
});
