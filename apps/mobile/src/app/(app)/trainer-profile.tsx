import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessTrainerProfile } from '@/features/auth/routeGuard';
import { TrainerProfileScreen } from '@/features/trainer/TrainerProfileScreen';

export default function TrainerProfileRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  // Deep-link guard: user must have trainer profile to access trainer profile screen
  if (!canAccessTrainerProfile(user)) {
    return <Redirect href="/(app)" />;
  }

  return <TrainerProfileScreen />;
}
