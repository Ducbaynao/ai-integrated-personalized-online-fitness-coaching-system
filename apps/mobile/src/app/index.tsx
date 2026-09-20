import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';

export default function Index() {
  const { status, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status === 'AUTHENTICATED') {
    return <Redirect href="/(app)" />;
  }

  return <Redirect href="/(auth)/sign-in" />;
}
