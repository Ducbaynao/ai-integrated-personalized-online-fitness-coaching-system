import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerCreateChangeProposalScreen } from '@/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen';

export default function TrainerCreateChangeProposalRoute() {
  const { relationshipId: rawId, occurrenceId } = useLocalSearchParams<{ relationshipId?: string; occurrenceId?: string }>();
  const relationshipId = normalizeCoachingId(rawId);
  if (!relationshipId || typeof occurrenceId !== 'string' || !occurrenceId)
    return <Redirect href={'/(app)/coaching' as never} />;
  return <TrainerCreateChangeProposalScreen key={`${relationshipId}:${occurrenceId}`} relationshipId={relationshipId} occurrenceId={occurrenceId} />;
}
