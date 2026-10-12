import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerScheduleProposalDetailScreen } from '@/features/workout-schedule-proposals/TrainerScheduleProposalDetailScreen';

export default function TrainerScheduleProposalDetailRoute() {
  const params = useLocalSearchParams<{ relationshipId?: string; proposalId?: string }>();
  const relationshipId = normalizeCoachingId(params.relationshipId);
  const proposalId = normalizeCoachingId(params.proposalId);
  if (!relationshipId || !proposalId) return <Redirect href={'/(app)/coaching' as never} />;
  return <TrainerScheduleProposalDetailScreen relationshipId={relationshipId} proposalId={proposalId} />;
}
