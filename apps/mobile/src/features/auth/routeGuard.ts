import { CurrentUserResponse } from '@/types/auth';

export type AppDestinationRoute = '/(app)' | '/(onboarding)/student';

/**
 * Resolves the appropriate destination route for an authenticated user.
 * In Milestone M1 (Student Onboarding):
 * - If user lacks Student Profile -> redirect to student onboarding
 * - If user has Student Profile -> redirect to main app
 *
 * Designed to be easily extensible for Trainer-only and multi-role in PROFILE-04.
 */
export function getAuthenticatedHomeRoute(user: CurrentUserResponse | null): AppDestinationRoute {
  if (user && !user.capabilities.hasStudentProfile) {
    return '/(onboarding)/student';
  }
  return '/(app)';
}

/**
 * Returns true if the user has active access to the main application.
 */
export function canAccessMainApp(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return user.capabilities.hasStudentProfile;
}

/**
 * Returns true if the user requires student onboarding.
 */
export function requiresStudentOnboarding(user: CurrentUserResponse | null): boolean {
  if (!user) return false;
  return !user.capabilities.hasStudentProfile;
}
