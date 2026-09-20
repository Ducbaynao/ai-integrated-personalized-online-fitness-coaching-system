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
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function RegisterScreen() {
  const router = useRouter();
  const { register } = useAuth();
  const isDark = useColorScheme() === 'dark';

  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleRegister = async () => {
    if (isSubmitting) return;

    // Client validation
    const errors: Record<string, string> = {};
    if (!displayName.trim()) {
      errors.displayName = 'Full name is required';
    }
    if (!email.trim()) {
      errors.email = 'Email is required';
    }
    if (!password) {
      errors.password = 'Password is required';
    } else if (password.length < 8) {
      errors.password = 'Password must be at least 8 characters';
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }

    setFieldErrors({});
    setErrorMessage(null);
    setIsSubmitting(true);

    try {
      await register({
        email: email.trim(),
        displayName: displayName.trim(),
        password,
      });

      // Redirect to verification screen with note
      router.replace({
        pathname: '/(auth)/verify-email',
        params: {
          registeredEmail: email.trim(),
          successMessage: 'Account created! Please check your email (Mailpit in dev) for the verification link or token.',
        },
      });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const map: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            map[fe.field] = fe.message;
          });
          setFieldErrors(map);
        }
        setErrorMessage(err.message || 'Registration failed. Please check your information.');
      } else {
        setErrorMessage('An unexpected error occurred. Please try again.');
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
          <Text style={[styles.title, { color: textColor }]}>Create Account</Text>
          <Text style={[styles.subtitle, { color: subtextColor }]}>
            Join AI Fitness Coaching for personalized fitness training
          </Text>

          {errorMessage && (
            <View
              style={styles.errorBanner}
              accessibilityRole="alert"
              accessibilityLiveRegion="polite">
              <Text style={styles.errorBannerText}>{errorMessage}</Text>
            </View>
          )}

          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Full Name</Text>
            <TextInput
              testID="display-name-input"
              accessibilityLabel="Full Name"
              accessibilityHint="Enter your full name"
              aria-invalid={!!fieldErrors.displayName}
              style={[
                styles.input,
                { color: textColor, borderColor: fieldErrors.displayName ? themeColors.dangerText : borderColor },
              ]}
              placeholder="Alex Smith"
              placeholderTextColor={subtextColor}
              value={displayName}
              onChangeText={(text) => {
                setDisplayName(text);
                if (fieldErrors.displayName) {
                  setFieldErrors((prev) => ({ ...prev, displayName: '' }));
                }
              }}
              editable={!isSubmitting}
            />
            {!!fieldErrors.displayName && (
              <Text style={styles.fieldErrorText}>{fieldErrors.displayName}</Text>
            )}
          </View>

          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Email</Text>
            <TextInput
              testID="email-input"
              accessibilityLabel="Email"
              accessibilityHint="Enter your email address"
              aria-invalid={!!fieldErrors.email}
              style={[
                styles.input,
                { color: textColor, borderColor: fieldErrors.email ? themeColors.dangerText : borderColor },
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
              accessibilityLabel="Password"
              accessibilityHint="Create a password with at least 8 characters"
              aria-invalid={!!fieldErrors.password}
              style={[
                styles.input,
                { color: textColor, borderColor: fieldErrors.password ? themeColors.dangerText : borderColor },
              ]}
              placeholder="At least 8 characters"
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
            testID="submit-register"
            accessibilityRole="button"
            accessibilityLabel="Register"
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
            onPress={handleRegister}
            disabled={isSubmitting}>
            {isSubmitting ? (
              <ActivityIndicator color={themeColors.textOnPrimary} />
            ) : (
              <Text style={styles.buttonText}>Register</Text>
            )}
          </Pressable>

          <View style={styles.footerLinks}>
            <Pressable
              testID="link-to-sign-in"
              accessibilityRole="link"
              accessibilityLabel="Already have an account? Sign In"
              onPress={() => router.push('/(auth)/sign-in')}
              style={styles.linkButton}
              disabled={isSubmitting}>
              <Text style={styles.linkText}>
                Already have an account? <Text style={styles.linkTextBold}>Sign In</Text>
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
