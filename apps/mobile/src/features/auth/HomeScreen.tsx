import React, { useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function HomeScreen() {
  const router = useRouter();
  const { user, logout } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  const handleLogout = async () => {
    if (isLoggingOut) return;
    setIsLoggingOut(true);
    try {
      await logout();
    } finally {
      setIsLoggingOut(false);
    }
  };

  const themeColors = getSemanticColors(isDark);
  const bg = themeColors.canvas;
  const cardBg = themeColors.surface;
  const textColor = themeColors.textPrimary;
  const subtextColor = themeColors.textSecondary;
  const borderColor = themeColors.border;

  const isDualRole =
    Boolean(user?.capabilities?.hasStudentProfile) &&
    Boolean(user?.capabilities?.hasTrainerProfile);

  const canCrossActivateTrainer =
    Boolean(user?.capabilities?.hasStudentProfile) &&
    !user?.capabilities?.hasTrainerProfile;

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: bg }]}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        {/* Capability Switcher for Dual-Role */}
        {isDualRole && <CapabilitySwitcher />}

        {/* Header Section */}
        <View style={styles.header}>
          <Text style={[styles.greeting, { color: textColor }]}>
            Welcome, {user?.displayName || 'Athlete'}!
          </Text>
          <Text style={[styles.subtitle, { color: subtextColor }]}>
            AI Integrated Personalized Online Fitness Coaching
          </Text>
        </View>

        {/* Account Details Card */}
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.sectionTitle, { color: textColor }]}>Account Details</Text>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Email</Text>
            <Text style={[styles.value, { color: textColor }]}>{user?.email ?? 'Unknown'}</Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Status</Text>
            <View style={styles.badge}>
              <Text style={styles.badgeText}>{user?.status ?? 'ACTIVE'}</Text>
            </View>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Roles</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.roles?.length ? user.roles.join(', ') : 'None'}
            </Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Timezone</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.timezone ?? 'Asia/Ho_Chi_Minh'}
            </Text>
          </View>
        </View>

        {/* Capabilities Card */}
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.sectionTitle, { color: textColor }]}>Capabilities</Text>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Student Profile</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.capabilities?.hasStudentProfile ? 'Active' : 'Not created'}
            </Text>
          </View>

          {user?.capabilities?.hasStudentProfile && (
            <Pressable
              testID="view-student-profile-button"
              accessibilityRole="button"
              accessibilityLabel="View and Edit Student Profile"
              style={[styles.profileButton, { borderColor }]}
              onPress={() => router.push('/profile')}>
              <Text style={[styles.profileButtonText, { color: themeColors.primary }]}>
                View & Edit Student Profile →
              </Text>
            </Pressable>
          )}

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Trainer Profile</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.capabilities?.hasTrainerProfile ? 'Active' : 'Not created'}
            </Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Coaching Authority</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.capabilities?.canCoach ? 'Authorized' : 'Unauthorized'}
            </Text>
          </View>
        </View>

        {/* Cross-activation Card (Student -> Trainer) */}
        {canCrossActivateTrainer && (
          <View
            testID="student-cross-activate-card"
            style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
            <Text style={[styles.sectionTitle, { color: textColor }]}>
              Become a Coach
            </Text>
            <Text style={[styles.cardDescription, { color: subtextColor }]}>
              Are you a fitness trainer? Activate your Trainer Profile on this account to build your
              coaching presence and prepare for verification.
            </Text>
            <Pressable
              testID="student-cross-activate-trainer-button"
              accessibilityRole="button"
              accessibilityLabel="Become a Trainer"
              onPress={() => router.push('/(onboarding)/trainer')}
              style={[styles.profileButton, { borderColor: themeColors.primary }]}>
              <Text style={[styles.profileButtonText, { color: themeColors.primary }]}>
                Set up Trainer Profile →
              </Text>
            </Pressable>
          </View>
        )}

        {/* Preferences Card */}
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.sectionTitle, { color: textColor }]}>Preferences</Text>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Measurement System</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.settings?.measurementSystem ?? 'METRIC'}
            </Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Week Starts On</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.settings?.weekStartsOn === 1 ? 'Monday' : 'Sunday'}
            </Text>
          </View>
        </View>

        {/* Logout Action */}
        <Pressable
          testID="logout-button"
          accessibilityRole="button"
          accessibilityLabel="Log Out"
          accessibilityState={{ disabled: isLoggingOut, busy: isLoggingOut }}
          style={({ pressed }) => [
            styles.logoutButton,
            {
              backgroundColor: isLoggingOut
                ? themeColors.dangerSurface
                : pressed
                ? themeColors.dangerPressed
                : themeColors.dangerText,
            },
          ]}
          onPress={handleLogout}
          disabled={isLoggingOut}>
          {isLoggingOut ? (
            <ActivityIndicator color={themeColors.textOnPrimary} />
          ) : (
            <Text style={styles.logoutButtonText}>Log Out</Text>
          )}
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    padding: layout.mobileScreenPadding,
    gap: spacing.lg,
  },
  header: {
    marginTop: spacing.sm,
    marginBottom: spacing.xs,
  },
  greeting: {
    ...typography.h1,
  },
  subtitle: {
    ...typography.bodySmall,
    marginTop: spacing.xxs,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.md,
  },
  sectionTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
  },
  cardDescription: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  label: {
    ...typography.bodySmall,
  },
  value: {
    ...typography.label,
  },
  badge: {
    backgroundColor: colors.brand[50],
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
  },
  badgeText: {
    ...typography.caption,
    color: colors.brand[700],
    fontWeight: '600',
  },
  profileButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.xs,
  },
  profileButtonText: {
    ...typography.label,
  },
  logoutButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: spacing.md,
    marginBottom: spacing.xxl,
  },
  logoutButtonText: {
    ...typography.label,
    color: semanticColors.textOnPrimary,
  },
});
