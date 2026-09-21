import React from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getAuthenticatedHomeRoute } from '@/features/auth/routeGuard';

export default function AuthLayout() {
  const { status, user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status === 'AUTHENTICATED') {
    return <Redirect href={getAuthenticatedHomeRoute(user) as any} />;
  }

  return (
    <Stack
      screenOptions={{
        headerShown: false,
      }}>
      <Stack.Screen name="sign-in" />
      <Stack.Screen name="register" />
      <Stack.Screen name="verify-email" />
    </Stack>
  );
}
