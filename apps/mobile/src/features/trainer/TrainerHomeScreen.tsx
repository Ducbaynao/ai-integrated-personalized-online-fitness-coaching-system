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
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  const handleLogout = async () => {
    if (isLoggingOut) {
      return;
    }

    setIsLoggingOut(true);
    try {
      await logout();
    } finally {
      setIsLoggingOut(false);
    }
  };

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
            KHÔNG GIAN HUẤN LUYỆN VIÊN
          </Text>
        </View>
        <Text style={[styles.title, { color: themeColors.textPrimary }]}>
          Chào {user?.displayName || user?.email}
        </Text>
        <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
          Quản lý hồ sơ nghề nghiệp và trạng thái quyền huấn luyện của bạn.
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
          Quyền huấn luyện: {canCoach ? 'Đã được cấp' : 'Chờ xác minh'}
        </Text>
        <Text
          style={[
            styles.cardDescription,
            { color: themeColors.textSecondary },
          ]}>
          {canCoach
            ? 'Bạn có thể nhận và huấn luyện học viên theo quyền hiện tại.'
            : 'Hồ sơ huấn luyện viên chưa tự động cấp quyền huấn luyện. Quản trị viên cần xác minh trước khi bạn có thể nhận học viên.'}
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
          Hồ sơ nghề nghiệp
        </Text>
        <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
          Xem và cập nhật tên hiển thị, giới thiệu, kinh nghiệm và trạng thái nhận học viên.
        </Text>
        <Pressable
          testID="trainer-view-profile-button"
          accessibilityRole="button"
          accessibilityLabel="Xem hoặc chỉnh sửa hồ sơ huấn luyện viên"
          onPress={() => router.push('/(app)/trainer-profile')}
          style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}>
          <Text style={styles.primaryButtonText}>Xem và chỉnh sửa hồ sơ</Text>
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
            Bạn cũng muốn tự tập luyện?
          </Text>
          <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
            Kích hoạt Hồ sơ học viên trên cùng tài khoản để theo dõi tập luyện và mục tiêu cá nhân.
          </Text>
          <Pressable
            testID="trainer-cross-activate-student-button"
            accessibilityRole="button"
            accessibilityLabel="Kích hoạt hồ sơ học viên"
            onPress={() => router.push('/(onboarding)/student')}
            style={[styles.secondaryButton, { borderColor: themeColors.primary }]}>
            <Text style={[styles.secondaryButtonText, { color: themeColors.primary }]}>
              Kích hoạt hồ sơ học viên
            </Text>
          </Pressable>
        </View>
      )}

      {/* Logout */}
      <View style={styles.footer}>
        <Pressable
          testID="trainer-logout-button"
          accessibilityRole="button"
          accessibilityLabel="Đăng xuất"
          accessibilityState={{ disabled: isLoggingOut, busy: isLoggingOut }}
          disabled={isLoggingOut}
          onPress={handleLogout}
          style={styles.logoutButton}>
          {isLoggingOut ? (
            <ActivityIndicator color={themeColors.textSecondary} />
          ) : (
            <Text style={[styles.logoutText, { color: themeColors.textSecondary }]}>Đăng xuất</Text>
          )}
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
