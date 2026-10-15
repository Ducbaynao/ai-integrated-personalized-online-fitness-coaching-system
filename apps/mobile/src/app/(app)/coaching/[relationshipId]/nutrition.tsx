import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { TrainerNutritionScreen } from '@/features/nutrition/TrainerNutritionScreen';
export default function TrainerNutritionRoute() {
  const { relationshipId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationship = useRelationshipDetail(relationshipId ?? '');
  const studentId = relationship.data?.relationship?.studentId;
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as never} />;
  if (!studentId) return null;
  return <TrainerNutritionScreen studentId={studentId} />;
}
