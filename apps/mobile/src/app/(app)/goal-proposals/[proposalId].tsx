import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessFitnessGoals } from '@/features/auth/routeGuard';
import { GoalProposalDetailScreen } from '@/features/goal/GoalProposalDetailScreen';

export default function GoalProposalDetailRoute() {
  const { user, isLoading } = useAuth();
  const { proposalId } = useLocalSearchParams<{ proposalId: string }>();

  if (isLoading) {
    return null;
  }

  // Deep-link guard: user must have student profile to view proposals
  if (!canAccessFitnessGoals(user)) {
    return <Redirect href="/(app)" />;
  }

  if (!proposalId) {
    return <Redirect href={"/(app)/goals" as any} />;
  }

  return <GoalProposalDetailScreen proposalId={proposalId} />;
}
