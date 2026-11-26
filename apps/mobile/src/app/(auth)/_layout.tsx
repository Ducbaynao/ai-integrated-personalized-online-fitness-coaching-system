import React from 'react';
import { Redirect, Stack } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getAuthenticatedHomeRoute } from '@/features/auth/routeGuard';
import { clearPendingCoachingQrIntent, getPendingCoachingQrIntent } from '@/features/coaching/pendingQrIntent';

export default function AuthLayout() {
  const { status, user, isLoading } = useAuth();

  if (isLoading) {
    return null;
  }

  if (status === 'AUTHENTICATED') {
    const home = getAuthenticatedHomeRoute(user);
    if (home !== '/(app)') {
      clearPendingCoachingQrIntent();
      return <Redirect href={home as any} />;
    }
    const pendingQr = getPendingCoachingQrIntent();
    if (pendingQr) {
      return (
        <Redirect
          href={{
            pathname: '/(app)/coaching/qr',
            params: {
              c: pendingQr.publicCode,
              v: String(pendingQr.payloadVersion),
              ...(pendingQr.targetRole ? { targetRole: pendingQr.targetRole } : {}),
            },
          } as any}
        />
      );
    }
    return <Redirect href={home as any} />;
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
