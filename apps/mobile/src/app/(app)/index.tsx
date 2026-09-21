import React from 'react';
import { useAuth } from '@/features/auth/AuthContext';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { TrainerHomeScreen } from '@/features/trainer/TrainerHomeScreen';

export default function AppHomeRoute() {
  const { activeCapability } = useAuth();

  if (activeCapability === 'TRAINER') {
    return <TrainerHomeScreen />;
  }

  return <HomeScreen />;
}
