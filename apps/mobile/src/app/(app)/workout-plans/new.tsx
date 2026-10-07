import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';

export default function NewWorkoutPlanRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  return <WorkoutPlanBuilderScreen mode="create" />;
}
