import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { StudentConfirmBatchScreen } from '@/features/workout-schedule/StudentConfirmBatchScreen';

export default function StudentConfirmBatchRoute() {
  const { user, isLoading } = useAuth();
  const { planId } = useLocalSearchParams<{ planId: string }>();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user) || !planId || typeof planId !== 'string') return <Redirect href="/(app)" />;
  return <StudentConfirmBatchScreen key={`${user?.id}:${planId}`} planId={planId} />;
}
