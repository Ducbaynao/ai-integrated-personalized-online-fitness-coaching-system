import React, { useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { getAuthErrorMessage } from '@/features/auth/authMessages';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function VerifyEmailScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{
    token?: string;
    registeredEmail?: string;
    successMessage?: string;
  }>();

  const { confirmEmail } = useAuth();
  const isDark = useColorScheme() === 'dark';

  const [enteredToken, setEnteredToken] = useState<string | null>(null);
  const token = enteredToken !== null ? enteredToken : params.token || '';
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isSuccess, setIsSuccess] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [dismissInfo, setDismissInfo] = useState(false);
  const infoMessage = !dismissInfo && params.successMessage ? params.successMessage : null;

  const handleVerify = async () => {
    if (isSubmitting) return;

    const trimmedToken = token.trim();
    if (!trimmedToken) {
      setErrorMessage('Vui lòng nhập mã xác minh.');
      return;
    }

    setErrorMessage(null);
    setDismissInfo(true);
    setIsSubmitting(true);

    try {
      await confirmEmail(trimmedToken);
      setIsSuccess(true);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrorMessage(
          getAuthErrorMessage(err, 'Xác minh thất bại. Mã có thể đã hết hạn hoặc đã được sử dụng.')
        );
      } else {
        setErrorMessage('Đã xảy ra lỗi ngoài dự kiến. Vui lòng thử lại.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const themeColors = getSemanticColors(isDark);
  const bg = themeColors.canvas;
  const cardBg = themeColors.surface;
  const textColor = themeColors.textPrimary;
  const subtextColor = themeColors.textSecondary;
  const borderColor = themeColors.border;

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      style={[styles.container, { backgroundColor: bg }]}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled">
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.title, { color: textColor }]}>Xác minh email</Text>
          <Text style={[styles.subtitle, { color: subtextColor }]}>
            Xác nhận địa chỉ email để kích hoạt tài khoản
          </Text>

          {infoMessage && (
            <View
              style={styles.infoBanner}
              accessibilityRole="summary"
              accessibilityLiveRegion="polite">
              <Text style={styles.infoBannerText}>{infoMessage}</Text>
            </View>
          )}

          {errorMessage && (
            <View
              style={styles.errorBanner}
              accessibilityRole="alert"
              accessibilityLiveRegion="polite">
              <Text style={styles.errorBannerText}>{errorMessage}</Text>
            </View>
          )}

          {isSuccess ? (
            <View style={styles.successContainer}>
              <View
                style={styles.successBanner}
                accessibilityRole="summary"
                accessibilityLiveRegion="polite">
                <Text style={styles.successBannerText}>
                  Email đã được xác minh thành công. Bạn có thể đăng nhập ngay bây giờ.
                </Text>
              </View>

              <Pressable
                testID="go-to-sign-in-button"
                accessibilityRole="button"
                accessibilityLabel="Tiếp tục đến màn hình đăng nhập"
                style={({ pressed }) => [
                  styles.primaryButton,
                  {
                    backgroundColor: pressed
                      ? themeColors.primaryPressed
                      : themeColors.primary,
                  },
                ]}
                onPress={() => router.replace('/(auth)/sign-in')}>
                <Text style={styles.buttonText}>Tiếp tục đăng nhập</Text>
              </Pressable>
            </View>
          ) : (
            <>
              <View style={styles.inputGroup}>
                <Text style={[styles.label, { color: textColor }]}>Mã xác minh</Text>
                <TextInput
                  testID="token-input"
                  accessibilityLabel="Mã xác minh"
                  accessibilityHint="Nhập hoặc dán mã xác minh nhận được trong email"
                  style={[styles.input, { color: textColor, borderColor }]}
                  placeholder="Dán hoặc nhập mã xác minh"
                  placeholderTextColor={subtextColor}
                  autoCapitalize="none"
                  autoCorrect={false}
                  multiline
                  value={token}
                  onChangeText={(text) => {
                    setEnteredToken(text);
                    if (errorMessage) setErrorMessage(null);
                  }}
                  editable={!isSubmitting}
                />
              </View>

              <Pressable
                testID="submit-verify"
                accessibilityRole="button"
                accessibilityLabel="Xác nhận mã xác minh"
                accessibilityState={{ disabled: isSubmitting, busy: isSubmitting }}
                style={({ pressed }) => [
                  styles.primaryButton,
                  {
                    backgroundColor: isSubmitting
                      ? colors.brand[300]
                      : pressed
                      ? themeColors.primaryPressed
                      : themeColors.primary,
                  },
                ]}
                onPress={handleVerify}
                disabled={isSubmitting}>
                {isSubmitting ? (
                  <ActivityIndicator color={themeColors.textOnPrimary} />
                ) : (
                  <Text style={styles.buttonText}>Xác nhận</Text>
                )}
              </Pressable>

              <View style={styles.footerLinks}>
                <Pressable
                  testID="back-to-sign-in"
                  accessibilityRole="link"
                  accessibilityLabel="Quay lại đăng nhập"
                  onPress={() => router.push('/(auth)/sign-in')}
                  style={styles.linkButton}
                  disabled={isSubmitting}>
                  <Text style={styles.linkText}>
                    Quay lại <Text style={styles.linkTextBold}>Đăng nhập</Text>
                  </Text>
                </Pressable>
              </View>
            </>
          )}
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    padding: layout.mobileScreenPadding,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
  },
  title: {
    ...typography.h2,
    marginBottom: spacing.xs,
  },
  subtitle: {
    ...typography.bodySmall,
    marginBottom: spacing.xl,
  },
  inputGroup: {
    marginBottom: spacing.lg,
  },
  label: {
    ...typography.label,
    marginBottom: spacing.xs,
  },
  input: {
    minHeight: layout.minimumTouchTarget * 1.5,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    textAlignVertical: 'top',
  },
  infoBanner: {
    backgroundColor: colors.brand[50],
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.lg,
  },
  infoBannerText: {
    ...typography.bodySmall,
    color: colors.brand[700],
  },
  errorBanner: {
    backgroundColor: semanticColors.dangerSurface,
    borderRadius: radius.md,
    padding: spacing.md,
    marginBottom: spacing.lg,
  },
  errorBannerText: {
    ...typography.bodySmall,
    color: semanticColors.dangerText,
  },
  successContainer: {
    gap: spacing.lg,
  },
  successBanner: {
    backgroundColor: semanticColors.successSurface,
    borderRadius: radius.md,
    padding: spacing.md,
  },
  successBannerText: {
    ...typography.bodySmall,
    color: semanticColors.successText,
  },
  primaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: spacing.sm,
    marginBottom: spacing.lg,
  },
  buttonText: {
    ...typography.label,
    color: semanticColors.textOnPrimary,
  },
  footerLinks: {
    alignItems: 'center',
  },
  linkButton: {
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    paddingVertical: spacing.xs,
  },
  linkText: {
    ...typography.bodySmall,
    color: semanticColors.textSecondary,
    textAlign: 'center',
  },
  linkTextBold: {
    color: colors.brand[600],
    fontWeight: '600',
  },
});
