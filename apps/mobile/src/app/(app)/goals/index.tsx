import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessFitnessGoals } from '@/features/auth/routeGuard';
import { CurrentGoalScreen } from '@/features/goal/CurrentGoalScreen';

export default function GoalsRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  // Deep-link guard: user must have student profile to manage goals
  if (!canAccessFitnessGoals(user)) {
    return <Redirect href="/(app)" />;
  }

  return <CurrentGoalScreen />;
}
