import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { StudentWorkoutScheduleDetailScreen } from '@/features/workout-schedule/StudentWorkoutScheduleDetailScreen';

export default function PlannedWorkoutDetailRoute() {
  const { user, isLoading } = useAuth();
  const { occurrenceId } = useLocalSearchParams<{ occurrenceId: string }>();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  const id = typeof occurrenceId === 'string' ? occurrenceId : '';
  return <StudentWorkoutScheduleDetailScreen key={`${user?.id}:${id}`} occurrenceId={id} />;
}
