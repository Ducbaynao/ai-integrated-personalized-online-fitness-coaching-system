import React from 'react';
import { Redirect, useLocalSearchParams, useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessAiKnowledge } from '@/features/auth/routeGuard';
import { AiAgentConversationScreen } from '@/features/ai-knowledge/AiAgentConversationScreen';

export default function AiAgentConversationRoute() {
  const { user, activeCapability, isLoading } = useAuth();
  const router = useRouter();
  const params = useLocalSearchParams<{
    conversationId?: string;
    studentId?: string;
  }>();

  if (isLoading) {
    return null;
  }

  if (!canAccessAiKnowledge(user)) {
    return <Redirect href="/(app)" />;
  }

  const actorRole = activeCapability === 'TRAINER' ? 'TRAINER' : 'STUDENT';

  return (
    <AiAgentConversationScreen
      initialConversationId={params.conversationId}
      subjectId={params.studentId}
      actorRole={actorRole}
      onBack={() => router.back()}
    />
  );
}
