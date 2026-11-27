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
import { fontFamily, typography } from '@/design-system/tokens/typography';

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
          Chào mừng đến với Digital Fitness
        </Text>
        <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
          Chọn cách bạn muốn bắt đầu. Bạn có thể kích hoạt vai trò còn lại sau trong cài đặt tài khoản.
        </Text>
      </View>

      <View style={styles.optionsContainer}>
        {/* Student Purpose Card */}
        <Pressable
          testID="select-student-purpose-button"
          accessibilityRole="button"
          accessibilityLabel="Tôi muốn tập luyện. Tiếp tục tạo hồ sơ Học viên."
          accessibilityHint="Tạo hồ sơ thể chất cá nhân cho Học viên."
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
                HỌC VIÊN
              </Text>
            </View>
          </View>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            Tôi muốn tập luyện
          </Text>
          <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
            Theo dõi buổi tập, đặt mục tiêu thể chất, thực hiện kế hoạch và lựa chọn tập cùng huấn luyện viên hoặc tự tập.
          </Text>
          <View style={styles.cardAction}>
            <Text style={[styles.cardActionText, { color: themeColors.primary }]}>
              Tạo hồ sơ Học viên →
            </Text>
          </View>
        </Pressable>

        {/* Trainer Purpose Card */}
        <Pressable
          testID="select-trainer-purpose-button"
          accessibilityRole="button"
          accessibilityLabel="Tôi muốn huấn luyện. Tiếp tục tạo hồ sơ Huấn luyện viên."
          accessibilityHint="Thiết lập hồ sơ huấn luyện viên chuyên nghiệp của bạn."
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
                HUẤN LUYỆN VIÊN
              </Text>
            </View>
          </View>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            Tôi muốn huấn luyện người khác
          </Text>
          <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
            Tạo hồ sơ Huấn luyện viên, khai báo kinh nghiệm và thông tin huấn luyện để chuẩn bị xác minh.
          </Text>
          <View style={styles.cardAction}>
            <Text style={[styles.cardActionText, { color: themeColors.primary }]}>
              Thiết lập hồ sơ huấn luyện viên →
            </Text>
          </View>
        </Pressable>
      </View>

      <View style={styles.footer}>
        <Text style={[styles.footerNote, { color: themeColors.textSecondary }]}>
          Đang đăng nhập bằng {user?.email}
        </Text>
        <Pressable
          testID="choose-purpose-logout-button"
          accessibilityRole="button"
          accessibilityLabel="Đăng xuất khỏi tài khoản của bạn"
          onPress={logout}
          style={styles.logoutButton}>
          <Text style={[styles.logoutText, { color: themeColors.textSecondary }]}>
            Đăng xuất
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
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.medium,
    textDecorationLine: 'underline',
  },
});
