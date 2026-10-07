import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerProgramScreen } from '@/features/workout-plan/TrainerProgramScreen';

export default function TrainerProgramRoute() {
  const params = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(params.relationshipId);
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as any} />;
  return <TrainerProgramScreen relationshipId={relationshipId} />;
}
