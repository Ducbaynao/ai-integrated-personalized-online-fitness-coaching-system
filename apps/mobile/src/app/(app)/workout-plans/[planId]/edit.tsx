import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { normalizeWorkoutPlanId } from '@/services/workoutPlanApi';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';

export default function EditWorkoutPlanRoute() {
  const { user, isLoading } = useAuth(); const params = useLocalSearchParams<{ planId?: string; versionId?: string; mode?: string }>();
  const planId = normalizeWorkoutPlanId(params.planId); const versionId = params.versionId ? normalizeWorkoutPlanId(params.versionId) : '';
  const mode = params.mode === 'publish' ? 'publish' : params.mode === 'draft' ? 'draft' : null;
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user) || !planId || !mode || (params.versionId && !versionId)) return <Redirect href={'/(app)/workout-plans' as any} />;
  return <WorkoutPlanBuilderScreen mode={mode} planId={planId} versionId={versionId || ''} />;
}
