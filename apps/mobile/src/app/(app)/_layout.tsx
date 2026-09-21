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
    </Stack>
  );
}
