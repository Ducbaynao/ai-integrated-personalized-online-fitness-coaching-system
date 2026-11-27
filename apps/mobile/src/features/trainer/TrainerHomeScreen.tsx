import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useAuth } from '@/features/auth/AuthContext';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { coachingQueryKeys } from '@/features/coaching/coachingQueries';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { queryClient } from '@/services/queryClient';
import { TrainerProfileResponse } from '@/types/trainer';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { fontFamily, typography } from '@/design-system/tokens/typography';

export function TrainerHomeScreen() {
  const router = useRouter();
  const { user, logout, refreshUser } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [trainerProfileState, setTrainerProfileState] = useState<{
    actorKey: string;
    profile: TrainerProfileResponse;
  } | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const isMountedRef = useRef(true);
  const canCoachValue = Boolean(user?.capabilities?.canCoach);
  const actorKey = user?.id ?? '';
  const trainerProfile =
    trainerProfileState?.actorKey === actorKey ? trainerProfileState.profile : null;
  const refreshUserRef = useRef(refreshUser);
  const canCoachRef = useRef(canCoachValue);
  const actorKeyRef = useRef(actorKey);
  const requestGenRef = useRef(0);
  const activeRefreshGenRef = useRef(0);

  useEffect(() => {
    refreshUserRef.current = refreshUser;
    canCoachRef.current = canCoachValue;
  }, [refreshUser, canCoachValue]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      requestGenRef.current += 1;
    };
  }, []);

  const loadTrainerStatus = useCallback(async ({ showRefreshing, forceRefreshAuth }: { showRefreshing: boolean; forceRefreshAuth: boolean }) => {
    const requestGen = ++requestGenRef.current;
    const startedActorKey = actorKeyRef.current;
    const isCurrentRequest = () =>
      isMountedRef.current &&
      requestGen === requestGenRef.current &&
      startedActorKey === actorKeyRef.current;

    if (showRefreshing) {
      activeRefreshGenRef.current = requestGen;
    }

    try {
      const profile = await trainerProfileApi.getMyTrainerProfile();
      if (!isCurrentRequest()) return;
      setTrainerProfileState({ actorKey: startedActorKey, profile });

      const needsCapabilitySync =
        forceRefreshAuth ||
        Boolean(profile.coachingEligibility?.eligible) !== canCoachRef.current ||
        profile.verificationStatus === 'VERIFIED';

      if (needsCapabilitySync) {
        try {
          await refreshUserRef.current?.();
        } catch {
          // Non-fatal: keep profile state if /users/me transiently fails.
        }
        if (!isCurrentRequest()) return;
        await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.all });
      }
    } catch {
      if (!isCurrentRequest()) return;
      if (forceRefreshAuth) {
        try {
          await refreshUserRef.current?.();
        } catch {
          // Ignore transient refresh failures.
        }
      }
    } finally {
      if (showRefreshing && isMountedRef.current && requestGen === activeRefreshGenRef.current) {
        setIsRefreshing(false);
      }
    }
  }, []);

  useEffect(() => {
    actorKeyRef.current = actorKey;
    void loadTrainerStatus({ showRefreshing: false, forceRefreshAuth: false });
  }, [actorKey, loadTrainerStatus]);

  const handleRefresh = useCallback(async () => {
    if (isRefreshing) return;
    setIsRefreshing(true);
    await loadTrainerStatus({ showRefreshing: true, forceRefreshAuth: true });
  }, [isRefreshing, loadTrainerStatus]);

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
  const verificationStatus = trainerProfile?.verificationStatus ?? (canCoach ? 'VERIFIED' : 'NOT_SUBMITTED');

  const authorityTitle = canCoach
    ? 'Quyền huấn luyện: Đã được cấp'
    : verificationStatus === 'PENDING'
      ? 'Quyền huấn luyện: Đang chờ Admin xét duyệt'
      : verificationStatus === 'REJECTED'
        ? 'Quyền huấn luyện: Hồ sơ bị từ chối'
        : verificationStatus === 'SUSPENDED'
          ? 'Quyền huấn luyện: Đang bị tạm ngưng'
          : 'Quyền huấn luyện: Chưa nộp hồ sơ xác minh';

  const authorityDescription = canCoach
    ? trainerProfile && !trainerProfile.acceptingStudents
      ? 'Hồ sơ đã được xác minh, nhưng bạn đang tắt nhận học viên nên học viên chưa tìm thấy bạn theo tên. Hãy bật chế độ nhận học viên trong hồ sơ.'
      : 'Bạn đã được xác minh và có thể nhận, huấn luyện học viên theo quyền hiện tại.'
    : verificationStatus === 'PENDING'
      ? 'Hồ sơ xác minh đã được gửi tới quản trị viên. Sau khi được phê duyệt và bật nhận học viên, học viên mới có thể tìm thấy bạn theo tên.'
      : verificationStatus === 'REJECTED'
        ? 'Hồ sơ xác minh trước đó bị từ chối. Hãy mở Hồ sơ xác minh để xem lý do, chỉnh sửa và gửi lại cho Admin.'
        : verificationStatus === 'SUSPENDED'
          ? 'Hồ sơ xác minh hoặc trạng thái hoạt động hiện bị tạm ngưng theo chính sách nền tảng.'
          : 'Tạo hồ sơ hoặc định danh công khai (public slug) chưa tự động cấp quyền huấn luyện hay đưa bạn vào danh bạ học viên. Hãy nộp hồ sơ xác minh để Admin xét duyệt.';

  const verificationCtaLabel = canCoach
    ? trainerProfile && !trainerProfile.acceptingStudents
      ? 'Bật nhận học viên trong hồ sơ'
      : null
    : verificationStatus === 'PENDING'
      ? 'Xem trạng thái hồ sơ xác minh'
      : verificationStatus === 'REJECTED'
        ? 'Xem lý do & gửi lại hồ sơ xác minh'
        : verificationStatus === 'SUSPENDED'
          ? 'Xem chi tiết hồ sơ xác minh'
          : 'Nộp hồ sơ xác minh';

  return (
    <SafeAreaView edges={['top']} style={[styles.container, { backgroundColor: themeColors.canvas }]}>
    <ScrollView
      testID="trainer-home-screen"
      contentContainerStyle={styles.scrollContent}
      refreshControl={<RefreshControl refreshing={isRefreshing} onRefresh={handleRefresh} />}
      style={styles.container}>
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
          Quản lý hồ sơ nghề nghiệp, nộp hồ sơ xác minh và trạng thái quyền huấn luyện của bạn.
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
          {authorityTitle}
        </Text>
        <Text
          style={[
            styles.cardDescription,
            { color: themeColors.textSecondary },
          ]}>
          {authorityDescription}
        </Text>
        <View style={styles.bannerActions}>
          {verificationCtaLabel ? (
            <Pressable
              testID="trainer-submit-verification-cta"
              accessibilityRole="button"
              accessibilityLabel={verificationCtaLabel}
              onPress={() => router.push('/(app)/trainer-profile')}
              style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}>
              <Text style={styles.primaryButtonText}>{verificationCtaLabel}</Text>
            </Pressable>
          ) : null}
          <Pressable
            testID="trainer-refresh-status-button"
            accessibilityRole="button"
            accessibilityLabel="Làm mới trạng thái xác minh"
            disabled={isRefreshing}
            onPress={handleRefresh}
            style={[styles.secondaryButton, { borderColor: themeColors.border, opacity: isRefreshing ? 0.6 : 1 }]}>
            {isRefreshing ? (
              <ActivityIndicator size="small" color={themeColors.primary} />
            ) : (
              <Text style={[styles.secondaryButtonText, { color: themeColors.primary }]}>Làm mới trạng thái</Text>
            )}
          </Pressable>
        </View>
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
          Hồ sơ nghề nghiệp & xác minh
        </Text>
        <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>
          Nộp hồ sơ xác minh cho Admin, quản lý định danh công khai (public slug), giới thiệu, kinh nghiệm và bật/tắt nhận học viên.
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

      <View
        style={[
          styles.card,
          { backgroundColor: themeColors.surface, borderColor: themeColors.border },
        ]}>
        <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Thư viện bài tập</Text>
        <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>Tra cứu danh mục bài tập theo nhóm cơ, thiết bị và độ khó.</Text>
        <Pressable
          testID="trainer-view-exercise-library-button"
          accessibilityRole="button"
          accessibilityLabel="Mở thư viện bài tập"
          onPress={() => router.push('/exercises' as any)}
          style={[styles.secondaryButton, { borderColor: themeColors.primary }]}>
          <Text style={[styles.secondaryButtonText, { color: themeColors.primary }]}>Mở thư viện bài tập</Text>
        </Pressable>
      </View>

      <View
        style={[
          styles.card,
          { backgroundColor: themeColors.surface, borderColor: themeColors.border },
        ]}>
        <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Quan hệ huấn luyện</Text>
        <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>Xử lý lời mời, yêu cầu và quản lý học viên theo trạng thái từ hệ thống.</Text>
        <Pressable
          testID="trainer-view-coaching-button"
          accessibilityRole="button"
          accessibilityLabel="Quản lý quan hệ huấn luyện"
          onPress={() => router.push('/coaching' as any)}
          style={[styles.secondaryButton, { borderColor: themeColors.primary }]}>
          <Text style={[styles.secondaryButtonText, { color: themeColors.primary }]}>Mở quan hệ huấn luyện</Text>
        </Pressable>
      </View>

      <View
        style={[
          styles.card,
          { backgroundColor: themeColors.surface, borderColor: themeColors.border },
        ]}>
        <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Kiến thức thể hình (AI)</Text>
        <Text style={[styles.cardDescription, { color: themeColors.textSecondary }]}>Tra cứu nguyên tắc tập luyện, phục hồi và kỹ thuật từ kho tri thức đã kiểm định.</Text>
        <Pressable
          testID="trainer-view-ai-knowledge-button"
          accessibilityRole="button"
          accessibilityLabel="Tra cứu kiến thức thể hình AI"
          onPress={() => router.push('/ai-knowledge' as any)}
          style={[styles.secondaryButton, { borderColor: themeColors.primary }]}>
          <Text style={[styles.secondaryButtonText, { color: themeColors.primary }]}>Tra cứu kiến thức thể hình</Text>
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
    </SafeAreaView>
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
    fontFamily: fontFamily.bold,
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
  bannerActions: {
    gap: spacing.sm,
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
    fontFamily: fontFamily.medium,
    textDecorationLine: 'underline',
  },
});
