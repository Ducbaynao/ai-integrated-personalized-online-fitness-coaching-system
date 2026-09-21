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
import { colors, getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';

export function ChoosePurposeScreen() {
  const router = useRouter();
  const { logout, user } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const handleSelectStudent = () => {
    router.push('/(onboarding)/student');
  };

  const handleSelectTrainer = () => {
    router.push('/(onboarding)/trainer');
  };

  return (
    <ScrollView
      testID="choose-purpose-screen"
      contentContainerStyle={styles.scrollContent}
      style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      <View style={styles.header}>
        <Text style={[styles.title, { color: themeColors.textPrimary }]}>
          Welcome to Digital Fitness
        </Text>
        <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
          Choose how you would like to start today. You can always activate the other capability
          later from your account settings.
        </Text>
      </View>

      <View style={styles.optionsContainer}>
        {/* Student Purpose Card */}
        <Pressable
          testID="select-student-purpose-button"
          accessibilityRole="button"
          accessibilityLabel="I want to be coached. Continue to Student Onboarding."
          accessibilityHint="Sets up your personal student fitness profile."
          onPress={handleSelectStudent}
          style={({ pressed }) => [
            styles.card,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
              opacity: pressed ? 0.85 : 1,
            },
          ]}>
          <View style={styles.cardBadgeContainer}>
            <View style={[styles.cardBadge, { backgroundColor: colors.brand[50] }]}>
              <Text style={[styles.cardBadgeText, { color: colors.brand[700] }]}>
                STUDENT
              </Text>
            </View>
          </View>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            I want to be coached
          </Text>
          <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
            Track workouts, set personal fitness goals, follow structured plans, and work with a
            coach or follow self-directed training.
          </Text>
          <View style={styles.cardAction}>
            <Text style={[styles.cardActionText, { color: themeColors.primary }]}>
              Start Student Onboarding →
            </Text>
          </View>
        </Pressable>

        {/* Trainer Purpose Card */}
        <Pressable
          testID="select-trainer-purpose-button"
          accessibilityRole="button"
          accessibilityLabel="I want to coach. Continue to Trainer Profile Setup."
          accessibilityHint="Sets up your professional trainer profile."
          onPress={handleSelectTrainer}
          style={({ pressed }) => [
            styles.card,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
              opacity: pressed ? 0.85 : 1,
            },
          ]}>
          <View style={styles.cardBadgeContainer}>
            <View style={[styles.cardBadge, { backgroundColor: colors.warning[100] }]}>
              <Text style={[styles.cardBadgeText, { color: colors.warning[600] }]}>
                TRAINER
              </Text>
            </View>
          </View>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            I want to coach others
          </Text>
          <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
            Build your professional trainer profile, set your experience and coaching details,
            and prepare for future verification.
          </Text>
          <View style={styles.cardAction}>
            <Text style={[styles.cardActionText, { color: themeColors.primary }]}>
              Set up Trainer Profile →
            </Text>
          </View>
        </Pressable>
      </View>

      <View style={styles.footer}>
        <Text style={[styles.footerNote, { color: themeColors.textSecondary }]}>
          Logged in as {user?.email}
        </Text>
        <Pressable
          testID="choose-purpose-logout-button"
          accessibilityRole="button"
          accessibilityLabel="Sign out of your account"
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
    flexGrow: 1,
    paddingHorizontal: layout.mobileScreenPadding,
    paddingTop: spacing.xxl,
    paddingBottom: spacing.xl,
    justifyContent: 'space-between',
  },
  header: {
    marginBottom: spacing.xl,
    alignItems: 'center',
  },
  title: {
    ...typography.h2,
    textAlign: 'center',
    marginBottom: spacing.sm,
  },
  subtitle: {
    ...typography.bodySmall,
    textAlign: 'center',
    maxWidth: 360,
  },
  optionsContainer: {
    gap: spacing.lg,
    marginBottom: spacing.xl,
  },
  card: {
    borderRadius: radius.xl,
    borderWidth: 1,
    padding: spacing.lg,
  },
  cardBadgeContainer: {
    flexDirection: 'row',
    marginBottom: spacing.sm,
  },
  cardBadge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
  },
  cardBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  cardTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
  },
  cardDescription: {
    ...typography.bodySmall,
    marginBottom: spacing.md,
  },
  cardAction: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  cardActionText: {
    ...typography.label,
  },
  footer: {
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.lg,
  },
  footerNote: {
    ...typography.caption,
  },
  logoutButton: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    ...typography.bodySmall,
    fontWeight: '500',
    textDecorationLine: 'underline',
  },
});
