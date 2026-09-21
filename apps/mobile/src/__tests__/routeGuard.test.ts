import {
  canAccessMainApp,
  canAccessPurposeSelection,
  canAccessStudentOnboarding,
  canAccessStudentProfile,
  canAccessTrainerOnboarding,
  canAccessTrainerProfile,
  getAuthenticatedHomeRoute,
  requiresOnboarding,
  requiresStudentOnboarding,
  requiresTrainerOnboarding,
  resolveActiveCapability,
} from '@/features/auth/routeGuard';
import { CurrentUserResponse, RoleCode } from '@/types/auth';

describe('Route Guard & Capability Resolution (PROFILE-04)', () => {
  const createUser = (
    hasStudentProfile: boolean,
    hasTrainerProfile: boolean = false,
    canCoach: boolean = false
  ): CurrentUserResponse => {
    const roles: RoleCode[] = [];
    if (hasStudentProfile) roles.push('STUDENT');
    if (hasTrainerProfile) roles.push('TRAINER');

    return {
      id: 'test-user-id',
      email: 'user@example.com',
      displayName: 'Test User',
      status: 'ACTIVE',
      preferredLocale: 'vi-VN',
      timezone: 'Asia/Ho_Chi_Minh',
      emailVerifiedAt: '2026-09-20T08:00:00Z',
      createdAt: '2026-09-20T08:00:00Z',
      phoneNumber: null,
      roles,
    capabilities: {
      hasStudentProfile,
      hasTrainerProfile,
      canCoach,
    },
    settings: {
      weekStartsOn: 1,
      measurementSystem: 'METRIC',
      accessibilityPreferences: {},
      privacyPreferences: {},
    },
  };
};

  // Scenario 1: No profiles
  it('Scenario 1: No profiles -> resolveActiveCapability returns null, getAuthenticatedHomeRoute returns /(onboarding)/select, canAccessMainApp returns false', () => {
    const user = createUser(false, false);
    expect(resolveActiveCapability(user)).toBeNull();
    expect(getAuthenticatedHomeRoute(user)).toBe('/(onboarding)/select');
    expect(canAccessMainApp(user)).toBe(false);
  });

  // Scenario 2: Student-only
  it('Scenario 2: Student-only -> returns STUDENT, getAuthenticatedHomeRoute returns /(app), canAccessMainApp returns true', () => {
    const user = createUser(true, false);
    expect(resolveActiveCapability(user)).toBe('STUDENT');
    expect(getAuthenticatedHomeRoute(user)).toBe('/(app)');
    expect(canAccessMainApp(user)).toBe(true);
  });

  // Scenario 3: Trainer-only
  it('Scenario 3: Trainer-only -> returns TRAINER, getAuthenticatedHomeRoute returns /(app), canAccessMainApp returns true', () => {
    const user = createUser(false, true);
    expect(resolveActiveCapability(user)).toBe('TRAINER');
    expect(getAuthenticatedHomeRoute(user)).toBe('/(app)');
    expect(canAccessMainApp(user)).toBe(true);
  });

  // Scenario 4: Dual-role preferred STUDENT
  it('Scenario 4: Dual-role preferred STUDENT -> returns STUDENT', () => {
    const user = createUser(true, true);
    expect(resolveActiveCapability(user, 'STUDENT')).toBe('STUDENT');
  });

  // Scenario 5: Dual-role preferred TRAINER
  it('Scenario 5: Dual-role preferred TRAINER -> returns TRAINER', () => {
    const user = createUser(true, true);
    expect(resolveActiveCapability(user, 'TRAINER')).toBe('TRAINER');
  });

  // Scenario 6: Dual-role preferred invalid/null -> defaults to STUDENT
  it('Scenario 6: Dual-role preferred invalid/null -> defaults to STUDENT', () => {
    const user = createUser(true, true);
    expect(resolveActiveCapability(user, null)).toBe('STUDENT');
    expect(resolveActiveCapability(user, undefined)).toBe('STUDENT');
    expect(resolveActiveCapability(user, 'INVALID_CAPABILITY')).toBe('STUDENT');
  });

  // Scenario 7: Student-only preferred TRAINER -> still returns STUDENT (cannot switch to non-existent profile)
  it('Scenario 7: Student-only preferred TRAINER -> still returns STUDENT', () => {
    const user = createUser(true, false);
    expect(resolveActiveCapability(user, 'TRAINER')).toBe('STUDENT');
  });

  // Scenario 8: Trainer-only preferred STUDENT -> still returns TRAINER (cannot switch to non-existent profile)
  it('Scenario 8: Trainer-only preferred STUDENT -> still returns TRAINER', () => {
    const user = createUser(false, true);
    expect(resolveActiveCapability(user, 'STUDENT')).toBe('TRAINER');
  });

  // Scenario 9: requiresOnboarding returns true only when no profiles
  it('Scenario 9: requiresOnboarding returns true only when no profiles', () => {
    expect(requiresOnboarding(null)).toBe(false);
    expect(requiresOnboarding(createUser(false, false))).toBe(true);
    expect(requiresOnboarding(createUser(true, false))).toBe(false);
    expect(requiresOnboarding(createUser(false, true))).toBe(false);
    expect(requiresOnboarding(createUser(true, true))).toBe(false);
  });

  // Scenario 10: Deep link permission checks
  it('Scenario 10: Deep link permission checks: canAccessStudentProfile, canAccessTrainerProfile, canAccessStudentOnboarding, canAccessTrainerOnboarding, canAccessPurposeSelection', () => {
    const noProfiles = createUser(false, false);
    const studentOnly = createUser(true, false);
    const trainerOnly = createUser(false, true);
    const dualRole = createUser(true, true);

    // canAccessStudentProfile: requires student profile
    expect(canAccessStudentProfile(null)).toBe(false);
    expect(canAccessStudentProfile(noProfiles)).toBe(false);
    expect(canAccessStudentProfile(studentOnly)).toBe(true);
    expect(canAccessStudentProfile(trainerOnly)).toBe(false);
    expect(canAccessStudentProfile(dualRole)).toBe(true);

    // canAccessTrainerProfile: requires trainer profile
    expect(canAccessTrainerProfile(null)).toBe(false);
    expect(canAccessTrainerProfile(noProfiles)).toBe(false);
    expect(canAccessTrainerProfile(studentOnly)).toBe(false);
    expect(canAccessTrainerProfile(trainerOnly)).toBe(true);
    expect(canAccessTrainerProfile(dualRole)).toBe(true);

    // canAccessStudentOnboarding: permitted only if NOT already student
    expect(canAccessStudentOnboarding(null)).toBe(false);
    expect(canAccessStudentOnboarding(noProfiles)).toBe(true);
    expect(canAccessStudentOnboarding(studentOnly)).toBe(false);
    expect(canAccessStudentOnboarding(trainerOnly)).toBe(true); // cross-activation
    expect(canAccessStudentOnboarding(dualRole)).toBe(false);

    // canAccessTrainerOnboarding: permitted only if NOT already trainer
    expect(canAccessTrainerOnboarding(null)).toBe(false);
    expect(canAccessTrainerOnboarding(noProfiles)).toBe(true);
    expect(canAccessTrainerOnboarding(studentOnly)).toBe(true); // cross-activation
    expect(canAccessTrainerOnboarding(trainerOnly)).toBe(false);
    expect(canAccessTrainerOnboarding(dualRole)).toBe(false);

    // canAccessPurposeSelection: permitted only when no profiles
    expect(canAccessPurposeSelection(null)).toBe(false);
    expect(canAccessPurposeSelection(noProfiles)).toBe(true);
    expect(canAccessPurposeSelection(studentOnly)).toBe(false);
    expect(canAccessPurposeSelection(trainerOnly)).toBe(false);
    expect(canAccessPurposeSelection(dualRole)).toBe(false);
  });

  describe('Backward compatibility helpers', () => {
    it('requiresStudentOnboarding returns true only if user lacks student profile', () => {
      expect(requiresStudentOnboarding(null)).toBe(false);
      expect(requiresStudentOnboarding(createUser(false, false))).toBe(true);
      expect(requiresStudentOnboarding(createUser(true, false))).toBe(false);
    });

    it('requiresTrainerOnboarding returns true only if user lacks trainer profile', () => {
      expect(requiresTrainerOnboarding(null)).toBe(false);
      expect(requiresTrainerOnboarding(createUser(false, false))).toBe(true);
      expect(requiresTrainerOnboarding(createUser(false, true))).toBe(false);
    });
  });
});
