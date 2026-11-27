import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import { NavigationAction } from '@/design-system/components/NavigationAction';
import { colors, getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing, iconSize } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { DEFAULT_APP_TIMEZONE, formatTimezoneDisplay } from '@/features/shared/timezoneUtils';

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

export function HomeScreen() {
  const router = useRouter();
  const { user, logout } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);
  const secondaryText = isDark ? theme.textSecondary : colors.neutral[700];
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

  const isDualRole =
    Boolean(user?.capabilities?.hasStudentProfile) &&
    Boolean(user?.capabilities?.hasTrainerProfile);

  const canCrossActivateTrainer =
    Boolean(user?.capabilities?.hasStudentProfile) &&
    !user?.capabilities?.hasTrainerProfile;

  const heading = (title: string) => (
    <Text accessibilityRole="header" aria-level={2} style={[styles.sectionTitle, { color: theme.textPrimary }]}>{title}</Text>
  );

  const detail = (label: string, value: React.ReactNode) => (
    <View style={styles.detailRow}>
      <Text style={[styles.label, { color: secondaryText }]}>{label}</Text>
      <View style={styles.detailValue}>{typeof value === 'string'
        ? <Text style={[styles.value, { color: theme.textPrimary }]}>{value}</Text> : value}</View>
    </View>
  );

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.canvas }]}>
      <ScrollView contentContainerStyle={styles.scrollContent}>
        <View style={styles.content}>
          {isDualRole && <CapabilitySwitcher />}
          <View style={styles.header}>
            <Text accessibilityRole="header" style={[styles.greeting, { color: theme.textPrimary }]}>
              Chào {user?.displayName || 'bạn'}!
            </Text>
            <Text style={[styles.subtitle, { color: secondaryText }]}>Không gian tập luyện cá nhân của bạn</Text>
          </View>

          {user?.capabilities?.hasStudentProfile && (
            <>
              <AppCard>
                <View style={styles.heroHeading}>
                  <View style={[styles.heroIcon, { backgroundColor: theme.brandSoft }]}>
                    <AppIcon name="workout" color={colors.brand[700]} size={iconSize.large} />
                  </View>
                  <View style={styles.heroText}>
                    {heading('Kế hoạch tập luyện')}
                    <Text style={[styles.subtitle, { color: secondaryText }]}>Xem và quản lý kế hoạch của bạn.</Text>
                  </View>
                </View>
                <AppButton testID="view-workout-plans-button" label="Mở kế hoạch tập luyện" icon="arrow"
                  onPress={() => router.push('/workout-plans' as any)} />
                <View style={styles.tileGrid}>
                  <NavigationAction testID="view-fitness-goals-button" title="Mục tiêu thể chất" icon="goal" tile
                    accessibilityLabel="Quản lý mục tiêu thể chất" onPress={() => router.push('/goals' as any)} />
                  <NavigationAction testID="view-exercise-library-button" title="Thư viện bài tập" icon="exercise" tile
                    accessibilityLabel="Mở thư viện bài tập" onPress={() => router.push('/exercises' as any)} />
                </View>
              </AppCard>

              <View style={styles.section}>
                {heading('Theo dõi mỗi ngày')}
                <NavigationAction testID="view-nutrition-button" title="Nhật ký dinh dưỡng" icon="nutrition"
                  accessibilityLabel="Mở nhật ký dinh dưỡng" onPress={() => router.push('/nutrition' as any)} />
                <View style={styles.tileGrid}>
                  <NavigationAction testID="view-progress-dashboard-button" title="Bảng điều khiển tiến độ" icon="progress" tile
                    accessibilityLabel="Xem bảng điều khiển tiến độ" onPress={() => router.push('/progress' as any)} />
                  <NavigationAction testID="view-measurements-button" title="Nhật ký số đo cơ thể" icon="measurement" tile
                    accessibilityLabel="Xem nhật ký số đo cơ thể" onPress={() => router.push('/measurements' as any)} />
                </View>
              </View>

              <View style={styles.section}>
                {heading('Đồng hành tập luyện')}
                <NavigationAction testID="view-coaching-button" title="Quan hệ huấn luyện" icon="coaching"
                  accessibilityLabel="Quản lý quan hệ huấn luyện" onPress={() => router.push('/coaching' as any)} />
                <AppCard>
                  {heading('Trợ lý AI')}
                  <Text style={[styles.subtitle, { color: secondaryText }]}>Tra cứu kiến thức và xem tóm tắt tiến độ.</Text>
                  <NavigationAction testID="view-ai-knowledge-button" title="Kiến thức thể hình (AI)" icon="knowledge"
                    accessibilityLabel="Tra cứu kiến thức thể hình AI" onPress={() => router.push('/ai-knowledge' as any)} />
                  <NavigationAction testID="view-progress-summary-button" title="Tóm tắt tiến độ (AI)" icon="summary"
                    accessibilityLabel="Xem tóm tắt tiến độ AI" onPress={() => router.push('/progress-summary' as any)} />
                </AppCard>
              </View>
            </>
          )}

          <AppCard>
            {heading('Thông tin tài khoản')}
            {detail('Email', user?.email ?? 'Chưa có')}
            {detail('Trạng thái', <View style={[styles.badge, { backgroundColor: theme.brandSoft }]}>
              <Text style={styles.badgeText}>{user?.status ? ACCOUNT_STATUS_LABELS[user.status] ?? user.status : 'Không xác định'}</Text>
            </View>)}
            {detail('Vai trò', user?.roles?.length ? user.roles.map((role) => ROLE_LABELS[role] ?? role).join(', ') : 'Chưa có')}
            {user?.timezone && user.timezone !== DEFAULT_APP_TIMEZONE
              ? detail('Múi giờ tài khoản', formatTimezoneDisplay(user.timezone)) : null}
            {user?.capabilities?.hasStudentProfile && (
              <AppButton testID="view-student-profile-button" label="Xem và chỉnh sửa hồ sơ" icon="profile" variant="secondary"
                accessibilityLabel="Xem và chỉnh sửa hồ sơ học viên" onPress={() => router.push('/profile')} />
            )}
          </AppCard>

          <AppCard>
            {heading('Hồ sơ và quyền sử dụng')}
            {detail('Hồ sơ học viên', user?.capabilities?.hasStudentProfile ? 'Đã kích hoạt' : 'Chưa tạo')}
            {detail('Hồ sơ huấn luyện viên', user?.capabilities?.hasTrainerProfile ? 'Đã kích hoạt' : 'Chưa tạo')}
            {detail('Quyền huấn luyện', user?.capabilities?.canCoach ? 'Đã được cấp' : 'Chưa được cấp')}
          </AppCard>

          {canCrossActivateTrainer && (
            <AppCard testID="student-cross-activate-card">
              {heading('Trở thành huấn luyện viên')}
              <Text style={[styles.subtitle, { color: secondaryText }]}>
                Kích hoạt Hồ sơ huấn luyện viên trên chính tài khoản này để chuẩn bị cho quy trình xác minh.
              </Text>
              <AppButton testID="student-cross-activate-trainer-button" label="Tạo hồ sơ huấn luyện viên" icon="coaching"
                variant="secondary" onPress={() => router.push('/(onboarding)/trainer')} />
            </AppCard>
          )}

          <AppButton testID="logout-button" label="Đăng xuất" icon="logout" variant="danger"
            loading={isLoggingOut} onPress={handleLogout} />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  scrollContent: { padding: layout.mobileScreenPadding, paddingBottom: spacing.xxl, alignItems: 'center' },
  content: { width: '100%', maxWidth: layout.mobileContentMaxWidth, gap: layout.sectionGap },
  header: { marginTop: spacing.sm, gap: spacing.xs },
  greeting: typography.h1,
  subtitle: typography.bodySmall,
  sectionTitle: typography.h3,
  section: { gap: spacing.md },
  heroHeading: { flexDirection: 'row', gap: spacing.md, alignItems: 'center' },
  heroIcon: { width: layout.featureIconContainer, height: layout.featureIconContainer, borderRadius: radius.lg, alignItems: 'center', justifyContent: 'center' },
  heroText: { flex: 1, gap: spacing.xs },
  tileGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.md },
  detailRow: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-start', gap: spacing.sm },
  label: { ...typography.bodySmall, flexBasis: '40%', flexGrow: 1 },
  detailValue: { flexBasis: '55%', flexGrow: 1, minWidth: 0, alignItems: 'flex-end' },
  value: { ...typography.label, textAlign: 'right', flexShrink: 1, maxWidth: '100%' },
  badge: { paddingHorizontal: spacing.sm, paddingVertical: spacing.xs, borderRadius: radius.full, maxWidth: '100%' },
  badgeText: { ...typography.caption, color: colors.brand[700], fontFamily: fontFamily.semiBold },
});
