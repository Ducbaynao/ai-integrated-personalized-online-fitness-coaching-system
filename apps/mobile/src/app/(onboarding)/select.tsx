import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessMainApp } from '@/features/auth/routeGuard';
import { ChoosePurposeScreen } from '@/features/onboarding/ChoosePurposeScreen';

export default function SelectPurposeRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  // If user already has any profile, redirect them to main app
  if (canAccessMainApp(user)) {
    return <Redirect href="/(app)" />;
  }

  return <ChoosePurposeScreen />;
}
