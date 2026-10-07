import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { StudentLookupScreen } from '@/features/coaching/StudentLookupScreen';

export default function InviteRoute() {
  const { user, activeCapability, isLoading } = useAuth();
  if (isLoading) return null;
  if (activeCapability !== 'TRAINER' || !user?.capabilities?.hasTrainerProfile || !user.capabilities.canCoach) return <Redirect href={'/(app)/coaching' as any} />;
  return <StudentLookupScreen />;
}
