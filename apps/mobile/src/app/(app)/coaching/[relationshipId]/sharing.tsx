import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { SharingPermissionsScreen } from '@/features/coaching/SharingPermissionsScreen';

export default function SharingRoute() {
  const params = useLocalSearchParams<{ relationshipId?: string }>();
  const id = normalizeCoachingId(params.relationshipId);
  if (!id) return <Redirect href={'/(app)/coaching' as any} />;
  return <SharingPermissionsScreen relationshipId={id} />;
}
