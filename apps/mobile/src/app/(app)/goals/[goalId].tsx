import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessFitnessGoals } from '@/features/auth/routeGuard';
import { CurrentGoalScreen } from '@/features/goal/CurrentGoalScreen';

export default function GoalDetailRoute() {
  const { user, isLoading } = useAuth();
  const { goalId } = useLocalSearchParams<{ goalId: string }>();

  if (isLoading) {
    return null;
  }

  // Deep-link guard: user must have student profile to view goal details
  if (!canAccessFitnessGoals(user)) {
    return <Redirect href="/(app)" />;
  }

  return <CurrentGoalScreen goalId={goalId} />;
}
