import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { StudentScheduleProposalsScreen } from '@/features/workout-schedule-proposals/StudentScheduleProposalsScreen';

export default function WorkoutScheduleProposalsRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  return <StudentScheduleProposalsScreen />;
}
