import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerStudentGoalScreen } from '@/features/coaching/TrainerStudentGoalScreen';

export default function TrainerStudentGoalRoute() {
  const params = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(params.relationshipId);
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as any} />;
  return <TrainerStudentGoalScreen relationshipId={relationshipId} />;
}
