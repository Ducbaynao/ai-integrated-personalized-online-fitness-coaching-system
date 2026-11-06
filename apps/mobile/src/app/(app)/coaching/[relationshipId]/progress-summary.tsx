import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessProgressSummary } from '@/features/auth/routeGuard';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { ProgressSummaryScreen } from '@/features/progress-summary/ProgressSummaryScreen';

export default function TrainerProgressSummaryRoute() {
  const { user, isLoading: authLoading } = useAuth();
  const { relationshipId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationship = useRelationshipDetail(relationshipId ?? '');
  const studentId = relationship.data?.relationship?.studentId;

  if (authLoading || relationship.isLoading) {
    return null;
  }

  if (!canAccessProgressSummary(user) || !relationshipId) {
    return <Redirect href={'/(app)/coaching' as never} />;
  }

  if (!studentId || relationship.isError) {
    return <Redirect href={'/(app)/coaching' as never} />;
  }

  return <ProgressSummaryScreen studentId={studentId} relationshipId={relationshipId} />;
}
