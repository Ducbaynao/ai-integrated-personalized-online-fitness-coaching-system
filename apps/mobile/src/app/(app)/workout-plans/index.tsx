import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { StudentWorkoutPlansScreen } from '@/features/workout-plan/StudentWorkoutPlansScreen';

export default function WorkoutPlansRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  return <StudentWorkoutPlansScreen />;
}
