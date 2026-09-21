import React from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';

export default function OnboardingLayout() {
  const { status, user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status !== 'AUTHENTICATED') {
    return <Redirect href="/(auth)/sign-in" />;
  }

  // If user already has both profiles, redirect to (app)
  if (user?.capabilities?.hasStudentProfile && user?.capabilities?.hasTrainerProfile) {
    return <Redirect href="/(app)" />;
  }

  return (
    <Stack
      screenOptions={{
        headerShown: false,
      }}>
      <Stack.Screen name="select" />
      <Stack.Screen name="student" />
      <Stack.Screen name="trainer" />
    </Stack>
  );
}
