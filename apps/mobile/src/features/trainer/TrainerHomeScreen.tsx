import React from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function TrainerHomeScreen() {
  const router = useRouter();
  const { user, logout } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const isDualRole =
    Boolean(user?.capabilities?.hasStudentProfile) &&
    Boolean(user?.capabilities?.hasTrainerProfile);

  const canCrossActivateStudent =
    Boolean(user?.capabilities?.hasTrainerProfile) &&
    !user?.capabilities?.hasStudentProfile;

  const canCoach = Boolean(user?.capabilities?.canCoach);

  return (
    <ScrollView
      testID="trainer-home-screen"
      contentContainerStyle={styles.scrollContent}
      style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      {/* Capability Switcher for Dual-Role */}
      {isDualRole && <CapabilitySwitcher />}

      {/* Header */}
      <View style={styles.header}>
        <View style={[styles.roleTag, { backgroundColor: colors.brand[50] }]}>
          <Text style={[styles.roleTagText, { color: colors.brand[700] }]}>
            TRAINER WORKSPACE
          </Text>
        </View>
        <Text style={[styles.title, { color: themeColors.textPrimary }]}>
          Welcome, {user?.displayName || user?.email}
        </Text>
        <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
          Trainer capability is active. Manage your coaching profile and details.
        </Text>
      </View>

      {/* Coaching Authority Invariant Banner */}
      <View
        testID="trainer-authority-banner"
        accessibilityRole="alert"
        style={[
          styles.card,
          {
            backgroundColor: themeColors.surfaceSubtle,
            borderColor: themeColors.border,
          },
        ]}>
        <Text
          style={[
            styles.cardTitle,
            { color: themeColors.textPrimary },
          ]}>
          Coaching Authority: {canCoach ? 'Authorized' : 'Disabled (Verification Required)'}
        </Text>
        <Text
          style={[
            styles.cardDescription,
            { color: themeColors.textSecondary },
          ]}>
          {canCoach
            ? 'Your coaching authority is active. You may accept and coach students.'
            : 'In accordance with platform governance, having a Trainer Profile does not grant coaching authority. Formal review and verification by platform administration are required before you can coach students.'}
        </Text>
      </View>

      {/* Profile Management Card */}
      <View
        style={[
          styles.card,
          {
            backgroundColor: themeColors.surface,
            borderColor: themeColors.border,
          },
        ]}>
        <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
          Professional Profile
        </Text>
        <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
          View and update your public handle, bio, coaching experience, and student acceptance status.
        </Text>
        <Pressable
          testID="trainer-view-profile-button"
          accessibilityRole="button"
          accessibilityLabel="View or edit your trainer profile"
          onPress={() => router.push('/(app)/trainer-profile')}
          style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}>
          <Text style={styles.primaryButtonText}>View & Edit Trainer Profile</Text>
        </Pressable>
      </View>

      {/* Cross-activation Card (Trainer -> Student) */}
      {canCrossActivateStudent && (
        <View
          testID="trainer-cross-activate-card"
          style={[
            styles.card,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
            },
          ]}>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            Want to train as a student?
          </Text>
          <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
            Activate your Student Profile on the same account to track workouts and set personal
            fitness goals.
          </Text>
          <Pressable
            testID="trainer-cross-activate-student-button"
            accessibilityRole="button"
            accessibilityLabel="Activate student profile"
            onPress={() => router.push('/(onboarding)/student')}
            style={[styles.secondaryButton, { borderColor: themeColors.primary }]}>
            <Text style={[styles.secondaryButtonText, { color: themeColors.primary }]}>
              Activate Student Profile
            </Text>
          </Pressable>
        </View>
      )}

      {/* Logout */}
      <View style={styles.footer}>
        <Pressable
          testID="trainer-logout-button"
          accessibilityRole="button"
          accessibilityLabel="Sign out"
          onPress={logout}
          style={styles.logoutButton}>
          <Text style={[styles.logoutText, { color: themeColors.textSecondary }]}>
            Sign Out
          </Text>
        </Pressable>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: layout.mobileScreenPadding,
    paddingTop: spacing.xl,
    paddingBottom: spacing.xxl,
    gap: spacing.lg,
  },
  header: {
    marginBottom: spacing.xs,
  },
  roleTag: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
    alignSelf: 'flex-start',
    marginBottom: spacing.sm,
  },
  roleTagText: {
    ...typography.caption,
    fontWeight: '700',
  },
  title: {
    ...typography.h2,
    marginBottom: spacing.xs,
  },
  subtitle: {
    ...typography.bodySmall,
  },
  card: {
    borderRadius: radius.xl,
    borderWidth: 1,
    padding: spacing.lg,
  },
  cardTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
  },
  cardDescription: {
    ...typography.bodySmall,
    marginBottom: spacing.md,
  },
  primaryButton: {
    borderRadius: radius.md,
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  primaryButtonText: {
    ...typography.label,
    color: semanticColors.textOnPrimary,
  },
  secondaryButton: {
    borderRadius: radius.md,
    borderWidth: 1,
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  secondaryButtonText: {
    ...typography.label,
  },
  footer: {
    alignItems: 'center',
    marginTop: spacing.md,
  },
  logoutButton: {
    minHeight: layout.minimumTouchTarget,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    ...typography.bodySmall,
    fontWeight: '500',
    textDecorationLine: 'underline',
  },
});
