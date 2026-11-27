import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
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
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { NavigationAction } from '@/design-system/components/NavigationAction';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { coachingQueryKeys } from '@/features/coaching/coachingQueries';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { queryClient } from '@/services/queryClient';
import { TrainerProfileResponse } from '@/types/trainer';
import { colors, getSemanticColors } from '@/design-system/tokens/colors';
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
      <ScrollView testID="trainer-home-screen" contentContainerStyle={styles.scrollContent}
        refreshControl={<RefreshControl refreshing={isRefreshing} onRefresh={handleRefresh} />} style={styles.container}>
        <View style={styles.content}>
          {isDualRole && <CapabilitySwitcher />}
          <View style={styles.header}>
            <View style={[styles.roleTag, { backgroundColor: themeColors.brandSoft }]}>
              <Text style={[styles.roleTagText, { color: colors.brand[700] }]}>KHÔNG GIAN HUẤN LUYỆN VIÊN</Text>
            </View>
            <Text accessibilityRole="header" style={[styles.title, { color: themeColors.textPrimary }]}>Chào {user?.displayName || user?.email}</Text>
            <Text style={[styles.subtitle, { color: themeColors.textPrimary }]}>Quản lý hồ sơ nghề nghiệp, nộp hồ sơ xác minh và trạng thái quyền huấn luyện của bạn.</Text>
          </View>

          <AppCard testID="trainer-authority-banner" accessibilityRole="alert" title={authorityTitle} icon="shield" message={authorityDescription}>
            {verificationCtaLabel ? <AppButton testID="trainer-submit-verification-cta" label={verificationCtaLabel}
              accessibilityLabel={verificationCtaLabel} icon="shield" onPress={() => router.push('/(app)/trainer-profile')} /> : null}
            <AppButton testID="trainer-refresh-status-button" label="Làm mới trạng thái" icon="refresh" variant="secondary"
              accessibilityLabel="Làm mới trạng thái xác minh" disabled={isRefreshing} loading={isRefreshing} onPress={handleRefresh} />
          </AppCard>

          <AppCard title="Quan hệ huấn luyện" icon="coaching" message="Xử lý lời mời, yêu cầu và quản lý học viên theo trạng thái từ hệ thống.">
            <AppButton testID="trainer-view-coaching-button" label="Mở quan hệ huấn luyện" icon="arrow"
              accessibilityLabel="Quản lý quan hệ huấn luyện" variant={canCoach ? 'primary' : 'secondary'}
              onPress={() => router.push('/coaching' as any)} />
          </AppCard>

          <View style={styles.section}>
            <Text accessibilityRole="header" aria-level={2} style={[styles.sectionTitle, { color: themeColors.textPrimary }]}>Công cụ huấn luyện</Text>
            <NavigationAction testID="trainer-view-exercise-library-button" title="Thư viện bài tập" icon="exercise"
              description="Tra cứu danh mục bài tập theo nhóm cơ, thiết bị và độ khó."
              accessibilityLabel="Mở thư viện bài tập" onPress={() => router.push('/exercises' as any)} />
            <NavigationAction testID="trainer-view-ai-knowledge-button" title="Kiến thức thể hình (AI)" icon="knowledge"
              description="Tra cứu nguyên tắc tập luyện, phục hồi và kỹ thuật từ kho tri thức đã kiểm định."
              accessibilityLabel="Tra cứu kiến thức thể hình AI" onPress={() => router.push('/ai-knowledge' as any)} />
          </View>

          <AppCard title="Hồ sơ nghề nghiệp & xác minh" icon="profile"
            message="Nộp hồ sơ xác minh cho Admin, quản lý định danh công khai (public slug), giới thiệu, kinh nghiệm và bật/tắt nhận học viên.">
            <AppButton testID="trainer-view-profile-button" label="Xem và chỉnh sửa hồ sơ" icon="edit" variant="secondary"
              accessibilityLabel="Xem hoặc chỉnh sửa hồ sơ huấn luyện viên" onPress={() => router.push('/(app)/trainer-profile')} />
          </AppCard>

          {canCrossActivateStudent && <AppCard testID="trainer-cross-activate-card" title="Bạn cũng muốn tự tập luyện?" icon="goal"
            message="Kích hoạt Hồ sơ học viên trên cùng tài khoản để theo dõi tập luyện và mục tiêu cá nhân.">
            <AppButton testID="trainer-cross-activate-student-button" label="Kích hoạt hồ sơ học viên" icon="plus" variant="secondary"
              accessibilityLabel="Kích hoạt hồ sơ học viên" onPress={() => router.push('/(onboarding)/student')} />
          </AppCard>}
          <AppButton testID="trainer-logout-button" label="Đăng xuất" accessibilityLabel="Đăng xuất" icon="logout" variant="danger"
            disabled={isLoggingOut} loading={isLoggingOut} onPress={handleLogout} />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  scrollContent: { padding: layout.mobileScreenPadding, paddingBottom: spacing.xxl, alignItems: 'center' },
  content: { width: '100%', maxWidth: layout.mobileContentMaxWidth, gap: layout.sectionGap },
  header: { gap: spacing.sm },
  roleTag: { alignSelf: 'flex-start', borderRadius: radius.full, paddingHorizontal: spacing.sm, paddingVertical: spacing.xs },
  roleTagText: { ...typography.caption, fontFamily: fontFamily.bold },
  title: typography.h1,
  subtitle: typography.bodySmall,
  sectionTitle: typography.h3,
  section: { gap: spacing.md },
});
