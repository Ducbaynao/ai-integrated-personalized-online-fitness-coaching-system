import { CurrentUserResponse } from '@/types/auth';

export type ActiveCapability = 'STUDENT' | 'TRAINER';

export type AppDestinationRoute =
  | '/(app)'
  | '/(onboarding)/select'
  | '/(onboarding)/student'
  | '/(onboarding)/trainer';

/**
 * Pure resolver determining the active capability based on user's profile states
 * and optionally a preferred or persisted capability choice.
 *
 * Scenarios:
 * 1. No profile: returns null
 * 2. Student-only: always returns 'STUDENT'
 * 3. Trainer-only: always returns 'TRAINER'
 * 4. Dual-role: returns preferred if valid ('STUDENT' or 'TRAINER'), otherwise defaults to 'STUDENT'
 */
export function resolveActiveCapability(
  user: CurrentUserResponse | null,
  preferred?: string | null
): ActiveCapability | null {
  if (!user) return null;

  const hasStudent = Boolean(user.capabilities?.hasStudentProfile);
  const hasTrainer = Boolean(user.capabilities?.hasTrainerProfile);

  if (!hasStudent && !hasTrainer) {
    return null;
  }

  if (hasStudent && !hasTrainer) {
    return 'STUDENT';
  }

  if (!hasStudent && hasTrainer) {
    return 'TRAINER';
  }

  // Dual-role
  if (preferred === 'TRAINER') {
    return 'TRAINER';
  }
  return 'STUDENT';
}

/**
 * Resolves the appropriate destination route for an authenticated user.
 * - If user has neither Student nor Trainer profile -> redirect to purpose selection (/(onboarding)/select)
 * - If user has at least one active profile -> redirect to main app (/(app))
 */
export function getAuthenticatedHomeRoute(user: CurrentUserResponse | null): AppDestinationRoute {
  if (user && !user.capabilities?.hasStudentProfile && !user.capabilities?.hasTrainerProfile) {
    return '/(onboarding)/select';
  }
  return '/(app)';
}

/**
 * Returns true if the user has active access to the main application
 * (has either Student Profile or Trainer Profile).
 */
export function canAccessMainApp(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return Boolean(user.capabilities?.hasStudentProfile || user.capabilities?.hasTrainerProfile);
}

/**
 * Returns true if the user has no profiles and requires initial onboarding purpose selection.
 */
export function requiresOnboarding(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities?.hasStudentProfile && !user.capabilities?.hasTrainerProfile;
}

/**
 * Deep link guard: check if user can access student profile screen.
 */
export function canAccessStudentProfile(user: CurrentUserResponse | null): boolean {
  return Boolean(user?.capabilities?.hasStudentProfile);
}

/**
 * Deep link guard: check if user can access trainer profile screen.
 */
export function canAccessTrainerProfile(user: CurrentUserResponse | null): boolean {
  return Boolean(user?.capabilities?.hasTrainerProfile);
}

/**
 * Guard: check if user can access student onboarding (only if they don't already have a student profile).
 */
export function canAccessStudentOnboarding(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities?.hasStudentProfile;
}

/**
 * Guard: check if user can access trainer onboarding (only if they don't already have a trainer profile).
 */
export function canAccessTrainerOnboarding(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities?.hasTrainerProfile;
}

/**
 * Guard: check if user can access purpose selection (only if they have neither profile).
 */
export function canAccessPurposeSelection(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities?.hasStudentProfile && !user.capabilities?.hasTrainerProfile;
}

/**
 * Backward compatibility: returns true if the user does not have a student profile.
 */
export function requiresStudentOnboarding(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities?.hasStudentProfile;
}

/**
 * Backward compatibility: returns true if the user does not have a trainer profile.
 */
export function requiresTrainerOnboarding(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities?.hasTrainerProfile;
}
