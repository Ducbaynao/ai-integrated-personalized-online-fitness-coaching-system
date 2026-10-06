import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { TrainerDirectoryScreen } from '@/features/coaching/TrainerDirectoryScreen';

export default function TrainersRoute() {
  const { user, activeCapability, isLoading } = useAuth();
  if (isLoading) return null;
  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) return <Redirect href={'/(app)/coaching' as any} />;
  return <TrainerDirectoryScreen />;
}
