import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { WorkoutPlanDetailScreen } from '@/features/workout-plan/WorkoutPlanDetailScreen';

export default function WorkoutPlanDetailRoute() {
  const params = useLocalSearchParams<{ studentId?: string; planId?: string; relationshipId?: string }>();
  const studentId = normalizeCoachingId(params.studentId);
  const planId = normalizeCoachingId(params.planId);
  const relationshipId = normalizeCoachingId(params.relationshipId);
  if (!studentId || !planId || !relationshipId) return <Redirect href={'/(app)/coaching' as any} />;
  return <WorkoutPlanDetailScreen studentId={studentId} planId={planId} relationshipId={relationshipId} />;
}
