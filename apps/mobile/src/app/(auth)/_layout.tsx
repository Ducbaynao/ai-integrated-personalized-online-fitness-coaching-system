import React from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';

export default function AuthLayout() {
  const { status, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status === 'AUTHENTICATED') {
    return <Redirect href="/(app)" />;
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
