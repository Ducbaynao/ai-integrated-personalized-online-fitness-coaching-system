import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessAiKnowledge } from '@/features/auth/routeGuard';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { AiKnowledgeScreen } from '@/features/ai-knowledge/AiKnowledgeScreen';

export default function TrainerAiKnowledgeRoute() {
  const { user, isLoading: authLoading } = useAuth();
  const { relationshipId } = useLocalSearchParams<{ relationshipId?: string }>();
  const relationship = useRelationshipDetail(relationshipId ?? '');
  const studentId = relationship.data?.relationship?.studentId;

  if (authLoading || relationship.isLoading) {
    return null;
  }

  if (!canAccessAiKnowledge(user) || !relationshipId) {
    return <Redirect href={'/(app)/coaching' as never} />;
  }

  if (!studentId || relationship.isError) {
    return <Redirect href={'/(app)/coaching' as never} />;
  }

  return <AiKnowledgeScreen studentId={studentId} relationshipId={relationshipId} />;
}
