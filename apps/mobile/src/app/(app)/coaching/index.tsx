import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessCoaching } from '@/features/auth/routeGuard';
import { CoachingHubScreen } from '@/features/coaching/CoachingHubScreen';

export default function CoachingRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  if (!canAccessCoaching(user)) return <Redirect href="/(app)" />;
  return <CoachingHubScreen />;
}
