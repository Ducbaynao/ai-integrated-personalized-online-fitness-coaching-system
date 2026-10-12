import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerScheduleProposalsScreen } from '@/features/workout-schedule-proposals/TrainerScheduleProposalsScreen';

export default function TrainerScheduleProposalsRoute() {
  const { relationshipId: rawId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(rawId);
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as never} />;
  return <TrainerScheduleProposalsScreen relationshipId={relationshipId} />;
}
