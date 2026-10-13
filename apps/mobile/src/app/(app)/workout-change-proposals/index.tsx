import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { StudentChangeProposalsScreen } from '@/features/workout-schedule-change-proposals/StudentChangeProposalsScreen';

export default function WorkoutChangeProposalsRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  return <StudentChangeProposalsScreen />;
}
