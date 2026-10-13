import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { normalizeCoachingId } from '@/services/coachingApi';
import { StudentChangeProposalDetailScreen } from '@/features/workout-schedule-change-proposals/StudentChangeProposalDetailScreen';

export default function WorkoutChangeProposalDetailRoute() {
  const { user, isLoading } = useAuth();
  const { proposalId } = useLocalSearchParams<{ proposalId: string }>();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  const normalized = normalizeCoachingId(proposalId);
  if (!normalized) return <Redirect href="/(app)/workout-change-proposals" />;
  return <StudentChangeProposalDetailScreen key={`${user?.id}:${normalized}`} proposalId={normalized} />;
}
