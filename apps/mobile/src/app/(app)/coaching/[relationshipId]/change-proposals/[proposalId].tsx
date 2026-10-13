import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerChangeProposalDetailScreen } from '@/features/workout-schedule-change-proposals/TrainerChangeProposalDetailScreen';

export default function TrainerChangeProposalDetailRoute() {
  const { relationshipId: rawId, proposalId } = useLocalSearchParams<{ relationshipId?: string; proposalId?: string }>();
  const relationshipId = normalizeCoachingId(rawId);
  if (!relationshipId || typeof proposalId !== 'string' || !proposalId)
    return <Redirect href={'/(app)/coaching' as never} />;
  return <TrainerChangeProposalDetailScreen key={`${relationshipId}:${proposalId}`} relationshipId={relationshipId} proposalId={proposalId} />;
}
