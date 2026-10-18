import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { AttentionReviewScreen } from '@/features/attention/AttentionReviewScreen';

export default function TrainerAttentionRoute() {
  const { relationshipId: rawId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(rawId);
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as never} />;
  return <AttentionReviewScreen relationshipId={relationshipId} />;
}
