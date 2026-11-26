import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { MyCoachingQrScreen } from '@/features/coaching/MyCoachingQrScreen';

export default function MyCoachingQrRoute() {
  const { user, isLoading } = useAuth();
  if (isLoading) return null;
  const hasAnyCapability =
    Boolean(user?.capabilities?.hasStudentProfile) || Boolean(user?.capabilities?.hasTrainerProfile);
  if (!hasAnyCapability) {
    return <Redirect href={'/(app)/coaching' as any} />;
  }
  return <MyCoachingQrScreen />;
}
