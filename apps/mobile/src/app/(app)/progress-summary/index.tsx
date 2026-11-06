import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessProgressSummary } from '@/features/auth/routeGuard';
import { ProgressSummaryScreen } from '@/features/progress-summary/ProgressSummaryScreen';

export default function StudentProgressSummaryRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (!canAccessProgressSummary(user)) {
    return <Redirect href="/(app)" />;
  }

  return <ProgressSummaryScreen />;
}
