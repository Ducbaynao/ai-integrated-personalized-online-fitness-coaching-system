import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { TrainerStudentProgressScreen } from '@/features/coaching/TrainerStudentProgressScreen';

export default function TrainerStudentProgressRoute() {
  const { user, isLoading: authLoading } = useAuth();
  const { relationshipId } = useLocalSearchParams<{ relationshipId?: string }>();

  if (authLoading) {
    return null;
  }

  if (!user || !relationshipId) {
    return <Redirect href={'/(app)/coaching' as never} />;
  }

  return <TrainerStudentProgressScreen relationshipId={relationshipId} />;
}
