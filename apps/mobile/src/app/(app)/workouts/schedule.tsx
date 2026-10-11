import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { StudentWorkoutScheduleScreen } from '@/features/workout-schedule/StudentWorkoutScheduleScreen';

export default function WorkoutScheduleRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  return <StudentWorkoutScheduleScreen />;
}
