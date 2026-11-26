import { useEffect } from 'react';
import { Linking, Platform } from 'react-native';
import { Stack } from 'expo-router';
import { useFonts } from 'expo-font';
import * as SplashScreen from 'expo-splash-screen';
import { QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { SessionGateScreen } from '@/features/auth/SessionGateScreen';
import { queryClient } from '@/services/queryClient';
import { PushReceiptListener } from '@/features/chat/PushReceiptListener';
import { appFontAssets } from '@/design-system/fonts';
import { parseCoachingQrUri } from '@/features/coaching/coachingQrCodec';
import { setPendingCoachingQrIntent } from '@/features/coaching/pendingQrIntent';

if (Platform.OS !== 'web') {
  SplashScreen.preventAutoHideAsync().catch(() => {});
}

function RootNavigator({ fontsReady }: { fontsReady: boolean }) {
  const { status, user, isLoading, restoreError, retrySessionRestore } = useAuth();

  useEffect(() => {
    const captureUrl = (url: string | null) => {
      if (!url || status === 'AUTHENTICATED') return;
      const parsed = parseCoachingQrUri(url);
      if (parsed.ok) {
        setPendingCoachingQrIntent({
          publicCode: parsed.publicCode,
          payloadVersion: parsed.payloadVersion,
        });
      }
    };
    Linking.getInitialURL().then(captureUrl).catch(() => {});
    const sub = Linking.addEventListener('url', ({ url }) => captureUrl(url));
    return () => sub.remove();
  }, [status]);

  useEffect(() => {
    if (fontsReady && !isLoading && Platform.OS !== 'web') {
      SplashScreen.hideAsync().catch(() => {
        // Ignored in environments where splash is not available
      });
    }
  }, [fontsReady, isLoading]);

  if (!fontsReady || isLoading) {
    return <SessionGateScreen mode="loading" />;
  }

  if (status === 'RESTORE_FAILED') {
    return (
      <SessionGateScreen
        mode="error"
        message={restoreError}
        onRetry={() => {
          retrySessionRestore().catch(() => {});
        }}
      />
    );
  }

  return (
    <>
    {status === 'AUTHENTICATED' && user ? <PushReceiptListener actor={user.id} /> : null}
    <Stack
      screenOptions={{
        headerShown: false,
      }}
    />
    </>
  );
}

export default function RootLayout() {
  // A load error must not block the app: text then falls back to the system font.
  const [fontsLoaded, fontError] = useFonts(appFontAssets);

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RootNavigator fontsReady={fontsLoaded || Boolean(fontError)} />
      </AuthProvider>
    </QueryClientProvider>
  );
}
