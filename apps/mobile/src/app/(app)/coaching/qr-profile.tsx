import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { QrProfilePreviewScreen } from '@/features/coaching/QrProfilePreviewScreen';

export default function CoachingQrProfileRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  const hasStudent = Boolean(user?.capabilities?.hasStudentProfile);
  const hasTrainer = Boolean(user?.capabilities?.hasTrainerProfile && user?.capabilities?.canCoach);
  if (!hasStudent && !hasTrainer) {
    return <Redirect href={'/(app)/coaching' as any} />;
  }
  return <QrProfilePreviewScreen />;
}
