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

const ACCOUNT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: 'Đang hoạt động',
  PENDING_VERIFICATION: 'Chờ xác minh',
  SUSPENDED: 'Đã tạm khóa',
  DISABLED: 'Đã vô hiệu hóa',
};

const ROLE_LABELS: Record<string, string> = {
  STUDENT: 'Học viên',
  TRAINER: 'Huấn luyện viên',
  ADMIN: 'Quản trị viên',
};

const MEASUREMENT_LABELS: Record<string, string> = {
  METRIC: 'Hệ mét',
  IMPERIAL: 'Hệ Anh',
};

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
            Chào {user?.displayName || 'bạn'}!
          </Text>
          <Text style={[styles.subtitle, { color: subtextColor }]}>
            Không gian tập luyện cá nhân của bạn
          </Text>
        </View>

        {/* Account Details Card */}
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.sectionTitle, { color: textColor }]}>Thông tin tài khoản</Text>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Email</Text>
            <Text style={[styles.value, { color: textColor }]}>{user?.email ?? 'Chưa có'}</Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Trạng thái</Text>
            <View style={styles.badge}>
              <Text style={styles.badgeText}>
                {user?.status
                  ? ACCOUNT_STATUS_LABELS[user.status] ?? user.status
                  : 'Không xác định'}
              </Text>
            </View>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Vai trò</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.roles?.length
                ? user.roles.map((role) => ROLE_LABELS[role] ?? role).join(', ')
                : 'Chưa có'}
            </Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Múi giờ</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.timezone ?? 'Asia/Ho_Chi_Minh'}
            </Text>
          </View>
        </View>

        {/* Capabilities Card */}
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.sectionTitle, { color: textColor }]}>Hồ sơ và quyền sử dụng</Text>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Hồ sơ học viên</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.capabilities?.hasStudentProfile ? 'Đã kích hoạt' : 'Chưa tạo'}
            </Text>
          </View>

          {user?.capabilities?.hasStudentProfile && (
            <Pressable
              testID="view-student-profile-button"
              accessibilityRole="button"
              accessibilityLabel="Xem và chỉnh sửa hồ sơ học viên"
              style={[styles.profileButton, { borderColor }]}
              onPress={() => router.push('/profile')}>
              <Text style={[styles.profileButtonText, { color: themeColors.primary }]}>
                Xem và chỉnh sửa hồ sơ →
              </Text>
            </Pressable>
          )}

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Hồ sơ huấn luyện viên</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.capabilities?.hasTrainerProfile ? 'Đã kích hoạt' : 'Chưa tạo'}
            </Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Quyền huấn luyện</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.capabilities?.canCoach ? 'Đã được cấp' : 'Chưa được cấp'}
            </Text>
          </View>
        </View>

        {/* Cross-activation Card (Student -> Trainer) */}
        {canCrossActivateTrainer && (
          <View
            testID="student-cross-activate-card"
            style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
            <Text style={[styles.sectionTitle, { color: textColor }]}>
              Trở thành huấn luyện viên
            </Text>
            <Text style={[styles.cardDescription, { color: subtextColor }]}>
              Kích hoạt Hồ sơ huấn luyện viên trên chính tài khoản này để chuẩn bị cho quy trình xác minh.
            </Text>
            <Pressable
              testID="student-cross-activate-trainer-button"
              accessibilityRole="button"
              accessibilityLabel="Tạo hồ sơ huấn luyện viên"
              onPress={() => router.push('/(onboarding)/trainer')}
              style={[styles.profileButton, { borderColor: themeColors.primary }]}>
              <Text style={[styles.profileButtonText, { color: themeColors.primary }]}>
                Tạo hồ sơ huấn luyện viên →
              </Text>
            </Pressable>
          </View>
        )}

        {/* Preferences Card */}
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          <Text style={[styles.sectionTitle, { color: textColor }]}>Tùy chọn</Text>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Hệ đo lường</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.settings?.measurementSystem
                ? MEASUREMENT_LABELS[user.settings.measurementSystem] ??
                  user.settings.measurementSystem
                : 'Hệ mét'}
            </Text>
          </View>

          <View style={styles.row}>
            <Text style={[styles.label, { color: subtextColor }]}>Ngày bắt đầu tuần</Text>
            <Text style={[styles.value, { color: textColor }]}>
              {user?.settings?.weekStartsOn === 1 ? 'Thứ Hai' : 'Chủ Nhật'}
            </Text>
          </View>
        </View>

        {/* Logout Action */}
        <Pressable
          testID="logout-button"
          accessibilityRole="button"
          accessibilityLabel="Đăng xuất"
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
            <Text style={styles.logoutButtonText}>Đăng xuất</Text>
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
