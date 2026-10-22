import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessAiKnowledge } from '@/features/auth/routeGuard';
import { AiKnowledgeScreen } from '@/features/ai-knowledge/AiKnowledgeScreen';

export default function AiKnowledgeRoute() {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (!canAccessAiKnowledge(user)) {
    return <Redirect href="/(app)" />;
  }

  return <AiKnowledgeScreen />;
}
