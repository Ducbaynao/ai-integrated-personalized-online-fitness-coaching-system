import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';

interface SessionGateScreenProps {
  mode: 'loading' | 'error';
  message?: string | null;
  onRetry?: () => void;
}

export function SessionGateScreen({ mode, message, onRetry }: SessionGateScreenProps) {
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);
  const isLoading = mode === 'loading';

  return (
    <View
      testID={isLoading ? 'session-loading-screen' : 'session-restore-error-screen'}
      accessibilityRole={isLoading ? 'progressbar' : 'alert'}
      style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      {isLoading ? <ActivityIndicator size="large" color={themeColors.primary} /> : null}
      <Text style={[styles.title, { color: themeColors.textPrimary }]}>
        {isLoading ? 'Đang khôi phục phiên đăng nhập' : 'Chưa thể kết nối'}
      </Text>
      <Text style={[styles.message, { color: themeColors.textSecondary }]}>
        {isLoading
          ? 'Vui lòng chờ trong giây lát.'
          : message ?? 'Không thể kiểm tra phiên đăng nhập. Hãy kiểm tra mạng và thử lại.'}
      </Text>
      {!isLoading && onRetry ? (
        <Pressable
          testID="retry-session-restore"
          accessibilityRole="button"
          accessibilityLabel="Thử khôi phục phiên đăng nhập lại"
          onPress={onRetry}
          style={({ pressed }) => [
            styles.button,
            { backgroundColor: pressed ? themeColors.primaryPressed : themeColors.primary },
          ]}>
          <Text style={[styles.buttonText, { color: themeColors.textOnPrimary }]}>Thử lại</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: layout.mobileScreenPadding,
    gap: spacing.md,
  },
  title: {
    fontSize: 20,
    lineHeight: 26,
    fontWeight: '700',
    textAlign: 'center',
  },
  message: {
    fontSize: 15,
    lineHeight: 22,
    textAlign: 'center',
    maxWidth: 420,
  },
  button: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.xl,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.sm,
  },
  buttonText: {
    fontSize: 15,
    lineHeight: 20,
    fontWeight: '700',
  },
});
