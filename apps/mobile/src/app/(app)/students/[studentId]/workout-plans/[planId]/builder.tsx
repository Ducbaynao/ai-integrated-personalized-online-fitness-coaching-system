import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';

export default function WorkoutPlanBuilderRoute() {
  const params = useLocalSearchParams<{ studentId?: string; planId?: string; relationshipId?: string; versionId?: string; mode?: string }>();
  const studentId = normalizeCoachingId(params.studentId);
  const planId = normalizeCoachingId(params.planId);
  const relationshipId = normalizeCoachingId(params.relationshipId);
  const versionId = params.versionId ? normalizeCoachingId(params.versionId) : '';
  const mode = params.mode === 'publish' ? 'publish' : params.mode === 'draft' ? 'draft' : null;
  if (!studentId || !planId || !relationshipId || !mode || (params.versionId && !versionId)) return <Redirect href={'/(app)/coaching' as any} />;
  return <WorkoutPlanBuilderScreen mode={mode} relationshipId={relationshipId} studentId={studentId} planId={planId} versionId={versionId || ''} />;
}
