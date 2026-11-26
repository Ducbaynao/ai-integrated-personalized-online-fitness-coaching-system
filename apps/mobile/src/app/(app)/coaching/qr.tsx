import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { CoachingQrScannerScreen } from '@/features/coaching/CoachingQrScannerScreen';

export default function CoachingQrRoute() {
  const { user, activeCapability, isLoading } = useAuth();
  if (isLoading) return null;
  const hasStudent = Boolean(user?.capabilities?.hasStudentProfile);
  const hasTrainer = Boolean(user?.capabilities?.hasTrainerProfile && user?.capabilities?.canCoach);
  if (activeCapability === 'TRAINER' && !hasTrainer && !hasStudent) {
    return <Redirect href={'/(app)/coaching' as any} />;
  }
  if (!hasStudent && !hasTrainer) {
    return <Redirect href={'/(app)/coaching' as any} />;
  }
  return <CoachingQrScannerScreen />;
}
