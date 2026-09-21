import React from 'react';
import { Redirect } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getAuthenticatedHomeRoute } from '@/features/auth/routeGuard';

export default function Index() {
  const { status, user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status === 'AUTHENTICATED') {
    return <Redirect href={getAuthenticatedHomeRoute(user) as any} />;
  }

  return <Redirect href="/(auth)/sign-in" />;
}
