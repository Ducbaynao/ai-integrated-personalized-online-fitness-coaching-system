import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { ProgressSummaryScreen } from '@/features/progress-summary/ProgressSummaryScreen';

export default function TrainerProgressSummaryRoute() {
  const { relationshipId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationship = useRelationshipDetail(relationshipId ?? '');
  const studentId = relationship.data?.relationship?.studentId;

  if (!relationshipId) {
    return <Redirect href={'/(app)/coaching' as never} />;
  }

  if (relationship.isLoading) {
    return null;
  }

  if (!studentId) {
    return null;
  }

  return <ProgressSummaryScreen studentId={studentId} relationshipId={relationshipId} />;
}
