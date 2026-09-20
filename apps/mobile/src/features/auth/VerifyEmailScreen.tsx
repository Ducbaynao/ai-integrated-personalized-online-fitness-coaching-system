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
import { colors, semanticColors } from '@/design-system/tokens/colors';
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
      setErrorMessage('Please enter the verification token');
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
          err.message || 'Verification failed. The token may be expired or already used.'
        );
      } else {
        setErrorMessage('An unexpected error occurred. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const bg = isDark ? '#12141A' : semanticColors.canvas;
  const cardBg = isDark ? '#1C1F26' : semanticColors.surface;
  const textColor = isDark ? '#FFFFFF' : semanticColors.textPrimary;
  const subtextColor = isDark ? '#8E95A5' : semanticColors.textSecondary;
  const borderColor = isDark ? '#2D323F' : semanticColors.border;

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      style={[styles.container, { backgroundColor: bg }]}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled">
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.title, { color: textColor }]}>Verify Email</Text>
          <Text style={[styles.subtitle, { color: subtextColor }]}>
            Confirm your email address to activate your account
          </Text>

          {infoMessage && (
            <View style={styles.infoBanner}>
              <Text style={styles.infoBannerText}>{infoMessage}</Text>
            </View>
          )}

          {errorMessage && (
            <View style={styles.errorBanner}>
              <Text style={styles.errorBannerText}>{errorMessage}</Text>
            </View>
          )}

          {isSuccess ? (
            <View style={styles.successContainer}>
              <View style={styles.successBanner}>
                <Text style={styles.successBannerText}>
                  Your email address has been successfully verified! You can now sign in to your
                  account.
                </Text>
              </View>

              <Pressable
                testID="go-to-sign-in-button"
                style={({ pressed }) => [
                  styles.primaryButton,
                  {
                    backgroundColor: pressed
                      ? semanticColors.primaryPressed
                      : semanticColors.primary,
                  },
                ]}
                onPress={() => router.replace('/(auth)/sign-in')}>
                <Text style={styles.buttonText}>Proceed to Sign In</Text>
              </Pressable>
            </View>
          ) : (
            <>
              <View style={styles.inputGroup}>
                <Text style={[styles.label, { color: textColor }]}>Verification Token</Text>
                <TextInput
                  testID="token-input"
                  style={[styles.input, { color: textColor, borderColor }]}
                  placeholder="Paste or enter verification token"
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
                style={({ pressed }) => [
                  styles.primaryButton,
                  {
                    backgroundColor: isSubmitting
                      ? colors.brand[300]
                      : pressed
                      ? semanticColors.primaryPressed
                      : semanticColors.primary,
                  },
                ]}
                onPress={handleVerify}
                disabled={isSubmitting}>
                {isSubmitting ? (
                  <ActivityIndicator color={semanticColors.textOnPrimary} />
                ) : (
                  <Text style={styles.buttonText}>Confirm Verification</Text>
                )}
              </Pressable>

              <View style={styles.footerLinks}>
                <Pressable
                  testID="back-to-sign-in"
                  onPress={() => router.push('/(auth)/sign-in')}
                  style={styles.linkButton}
                  disabled={isSubmitting}>
                  <Text style={styles.linkText}>
                    Back to <Text style={styles.linkTextBold}>Sign In</Text>
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
