import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';

export default function TrainersRoute() {
  const { user, activeCapability, isLoading } = useAuth();
  if (isLoading) return null;
  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) {
    return <Redirect href={'/(app)/coaching' as any} />;
  }
  return <Redirect href={'/(app)/coaching/qr?targetRole=TRAINER' as any} />;
}
