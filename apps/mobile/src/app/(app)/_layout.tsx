import React from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessMainApp, getAuthenticatedHomeRoute } from '@/features/auth/routeGuard';

export default function AppLayout() {
  const { status, user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status !== 'AUTHENTICATED') {
    return <Redirect href="/(auth)/sign-in" />;
  }

  // Redirect to onboarding purpose selection if user has no profiles
  if (!canAccessMainApp(user)) {
    return <Redirect href={getAuthenticatedHomeRoute(user) as any} />;
  }

  return (
    <Stack
      screenOptions={{
        headerShown: false,
      }}>
      <Stack.Screen name="index" />
      <Stack.Screen name="profile" />
      <Stack.Screen name="trainer-profile" />
      <Stack.Screen name="goals/index" />
      <Stack.Screen name="goals/[goalId]" />
      <Stack.Screen name="goal-proposals/[proposalId]" />
      <Stack.Screen name="exercises/index" />
      <Stack.Screen name="exercises/[exerciseId]" />
      <Stack.Screen name="coaching/index" />
      <Stack.Screen name="coaching/trainers" />
      <Stack.Screen name="coaching/invite" />
      <Stack.Screen name="coaching/[relationshipId]/index" />
      <Stack.Screen name="coaching/[relationshipId]/sharing" />
      <Stack.Screen name="workout-plans/index" />
      <Stack.Screen name="workout-plans/new" />
      <Stack.Screen name="workout-plans/[planId]/index" />
      <Stack.Screen name="workout-plans/[planId]/history" />
      <Stack.Screen name="workout-plans/[planId]/edit" />
      <Stack.Screen name="coaching/[relationshipId]/program" />
      <Stack.Screen name="coaching/[relationshipId]/workout-plans/new" />
      <Stack.Screen name="students/[studentId]/workout-plans/[planId]/index" />
      <Stack.Screen name="students/[studentId]/workout-plans/[planId]/builder" />
    </Stack>
  );
}
