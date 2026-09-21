import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { TrainerOnboardingScreen } from '@/features/trainer/TrainerOnboardingScreen';

export default function TrainerOnboardingRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  // If user already has trainer profile, redirect to (app)
  if (user?.capabilities?.hasTrainerProfile) {
    return <Redirect href="/(app)" />;
  }

  return <TrainerOnboardingScreen />;
}
