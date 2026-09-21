import React from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { canAccessMainApp } from '@/features/auth/routeGuard';

export default function OnboardingLayout() {
  const { status, user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status !== 'AUTHENTICATED') {
    return <Redirect href="/(auth)/sign-in" />;
  }

  // If user already has student profile, redirect them to (app)
  if (canAccessMainApp(user)) {
    return <Redirect href="/(app)" />;
  }

  return (
    <Stack
      screenOptions={{
        headerShown: false,
      }}>
      <Stack.Screen name="student" />
    </Stack>
  );
}
