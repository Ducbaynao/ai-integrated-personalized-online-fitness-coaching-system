import { useEffect } from 'react';
import { Platform } from 'react-native';
import { Stack } from 'expo-router';
import { useFonts } from 'expo-font';
import {
  DMSans_400Regular,
  DMSans_500Medium,
  DMSans_600SemiBold,
  DMSans_700Bold,
} from '@expo-google-fonts/dm-sans';
import * as SplashScreen from 'expo-splash-screen';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { SessionGateScreen } from '@/features/auth/SessionGateScreen';

if (Platform.OS !== 'web') {
  SplashScreen.preventAutoHideAsync().catch(() => {});
}

function RootNavigator({ fontsReady }: { fontsReady: boolean }) {
  const { status, isLoading, restoreError, retrySessionRestore } = useAuth();

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
    <Stack
      screenOptions={{
        headerShown: false,
      }}
    />
  );
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    DMSans_400Regular,
    DMSans_500Medium,
    DMSans_600SemiBold,
    DMSans_700Bold,
  });

  return (
    <AuthProvider>
      <RootNavigator fontsReady={fontsLoaded || Boolean(fontError)} />
    </AuthProvider>
  );
}
