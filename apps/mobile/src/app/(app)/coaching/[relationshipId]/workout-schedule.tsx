import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { normalizeCoachingId } from '@/services/coachingApi';
import { TrainerWorkoutScheduleScreen } from '@/features/workout-schedule/TrainerWorkoutScheduleScreen';

export default function TrainerWorkoutScheduleRoute() {
  const { relationshipId: rawId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationshipId = normalizeCoachingId(rawId);
  if (!relationshipId) return <Redirect href={'/(app)/coaching' as never} />;
  return <TrainerWorkoutScheduleScreen relationshipId={relationshipId} />;
}
