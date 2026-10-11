import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessWorkoutPlans } from '@/features/auth/routeGuard';
import { normalizeCoachingId } from '@/services/coachingApi';
import { StudentScheduleProposalDetailScreen } from '@/features/workout-schedule-proposals/StudentScheduleProposalDetailScreen';

export default function WorkoutScheduleProposalDetailRoute() {
  const { user, isLoading } = useAuth();
  const { proposalId } = useLocalSearchParams<{ proposalId: string }>();
  if (isLoading) return null;
  if (!canAccessWorkoutPlans(user)) return <Redirect href="/(app)" />;
  const normalized = normalizeCoachingId(proposalId);
  if (!normalized) return <Redirect href="/(app)/workout-schedule-proposals" />;
  return <StudentScheduleProposalDetailScreen proposalId={normalized} />;
}
