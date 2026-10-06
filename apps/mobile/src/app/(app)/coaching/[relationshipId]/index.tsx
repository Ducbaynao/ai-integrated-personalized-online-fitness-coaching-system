import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { RelationshipDetailScreen } from '@/features/coaching/RelationshipDetailScreen';

export default function RelationshipRoute() {
  const params = useLocalSearchParams<{ relationshipId?: string }>();
  const id = normalizeCoachingId(params.relationshipId);
  if (!id) return <Redirect href={'/(app)/coaching' as any} />;
  return <RelationshipDetailScreen relationshipId={id} />;
}
