import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { normalizeWorkoutPlanId } from '@/services/workoutPlanApi';
import { WorkoutPlanHistoryScreen } from '@/features/workout-plan/WorkoutPlanHistoryScreen';

export default function WorkoutPlanHistoryRoute() {
  const { user, isLoading } = useAuth(); const params = useLocalSearchParams<{ planId?: string }>(); const planId = normalizeWorkoutPlanId(params.planId);
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user) || !planId) return <Redirect href={'/(app)/workout-plans' as any} />;
  return <WorkoutPlanHistoryScreen planId={planId} />;
}
