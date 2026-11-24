import React from 'react';
import { Redirect, useLocalSearchParams } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessAiKnowledge } from '@/features/auth/routeGuard';
import { AiKnowledgeScreen } from '@/features/ai-knowledge/AiKnowledgeScreen';

export default function AiKnowledgeRoute() {
  const { user, isLoading } = useAuth();
  const params = useLocalSearchParams<{
    studentId?: string;
    relationshipId?: string;
    runId?: string;
  }>();

  if (isLoading) {
    return null;
  }

  if (!canAccessAiKnowledge(user)) {
    return <Redirect href="/(app)" />;
  }

  return (
    <AiKnowledgeScreen
      studentId={params.studentId}
      relationshipId={params.relationshipId}
      initialRunId={params.runId}
    />
  );
}
