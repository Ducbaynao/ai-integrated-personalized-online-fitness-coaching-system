import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerChangeProposalsScreen } from '@/features/workout-schedule-change-proposals/TrainerChangeProposalsScreen';

export default function TrainerChangeProposalsRoute() {
  const { relationshipId: rawId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(rawId);
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as never} />;
  return <TrainerChangeProposalsScreen relationshipId={relationshipId} />;
}
