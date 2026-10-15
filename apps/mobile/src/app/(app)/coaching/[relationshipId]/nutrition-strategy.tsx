import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { NutritionStrategyScreen } from '@/features/nutrition/NutritionStrategyScreen';

export default function TrainerNutritionStrategyRoute() {
  const { relationshipId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationship = useRelationshipDetail(relationshipId ?? '');
  const studentId = relationship.data?.relationship?.studentId;
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as never} />;
  if (!studentId) return null;
  return <NutritionStrategyScreen studentId={studentId} relationshipId={relationshipId} />;
}
