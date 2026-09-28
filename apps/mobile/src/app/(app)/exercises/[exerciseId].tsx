import React from 'react';
import { useLocalSearchParams } from 'expo-router';
import { ExerciseDetailScreen } from '@/features/exercise/ExerciseDetailScreen';

export default function ExerciseDetailRoute() {
  const { exerciseId } = useLocalSearchParams<{ exerciseId?: string | string[] }>();

  return <ExerciseDetailScreen exerciseId={exerciseId} />;
}
