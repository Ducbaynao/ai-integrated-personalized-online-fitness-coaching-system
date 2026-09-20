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
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { colors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function SignInScreen() {
  const router = useRouter();
  const { login } = useAuth();
  const isDark = useColorScheme() === 'dark';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleSignIn = async () => {
    if (isSubmitting) return;

    // Client validation
    const errors: Record<string, string> = {};
    if (!email.trim()) {
      errors.email = 'Email is required';
    }
    if (!password) {
      errors.password = 'Password is required';
    }
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }

    setFieldErrors({});
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      await login({
        email: email.trim(),
        password,
        deviceName: Platform.OS === 'android' ? 'Android Device' : 'iOS Device',
      });
      // Navigation is handled automatically by auth state guard in layout
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const map: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            map[fe.field] = fe.message;
          });
          setFieldErrors(map);
        }
        setErrorMessage(err.message || 'Login failed. Please check your credentials.');
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
          <Text style={[styles.title, { color: textColor }]}>Welcome Back</Text>
          <Text style={[styles.subtitle, { color: subtextColor }]}>
            Sign in to your AI Fitness Coaching account
          </Text>

          {errorMessage && (
            <View style={styles.errorBanner}>
              <Text style={styles.errorBannerText}>{errorMessage}</Text>
            </View>
          )}

          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Email</Text>
            <TextInput
              testID="email-input"
              style={[
                styles.input,
                { color: textColor, borderColor: fieldErrors.email ? semanticColors.dangerText : borderColor },
              ]}
              placeholder="you@example.com"
              placeholderTextColor={subtextColor}
              keyboardType="email-address"
              autoCapitalize="none"
              autoCorrect={false}
              value={email}
              onChangeText={(text) => {
                setEmail(text);
                if (fieldErrors.email) {
                  setFieldErrors((prev) => ({ ...prev, email: '' }));
                }
              }}
              editable={!isSubmitting}
            />
            {!!fieldErrors.email && (
              <Text style={styles.fieldErrorText}>{fieldErrors.email}</Text>
            )}
          </View>

          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Password</Text>
            <TextInput
              testID="password-input"
              style={[
                styles.input,
                { color: textColor, borderColor: fieldErrors.password ? semanticColors.dangerText : borderColor },
              ]}
              placeholder="••••••••"
              placeholderTextColor={subtextColor}
              secureTextEntry
              value={password}
              onChangeText={(text) => {
                setPassword(text);
                if (fieldErrors.password) {
                  setFieldErrors((prev) => ({ ...prev, password: '' }));
                }
              }}
              editable={!isSubmitting}
            />
            {!!fieldErrors.password && (
              <Text style={styles.fieldErrorText}>{fieldErrors.password}</Text>
            )}
          </View>

          <Pressable
            testID="submit-sign-in"
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
            onPress={handleSignIn}
            disabled={isSubmitting}>
            {isSubmitting ? (
              <ActivityIndicator color={semanticColors.textOnPrimary} />
            ) : (
              <Text style={styles.buttonText}>Sign In</Text>
            )}
          </Pressable>

          <View style={styles.footerLinks}>
            <Pressable
              testID="link-to-register"
              onPress={() => router.push('/(auth)/register')}
              style={styles.linkButton}
              disabled={isSubmitting}>
              <Text style={styles.linkText}>
                {"Don't have an account? "}
                <Text style={styles.linkTextBold}>Register</Text>
              </Text>
            </Pressable>

            <Pressable
              testID="link-to-verify-email"
              onPress={() => router.push('/(auth)/verify-email')}
              style={styles.linkButton}
              disabled={isSubmitting}>
              <Text style={[styles.linkText, { color: colors.brand[500] }]}>
                Have a verification token? Verify Email
              </Text>
            </Pressable>
          </View>
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
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    ...typography.body,
  },
  fieldErrorText: {
    ...typography.caption,
    color: semanticColors.dangerText,
    marginTop: spacing.xxs,
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
    gap: spacing.sm,
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
