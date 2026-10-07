import React, { useMemo } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from './components/WorkoutUi';
import { useCurrentWorkoutPlan, useWorkoutPlans, useWorkoutPlanVersion } from './workoutPlanQueries';
import { isCurrentPlanMissing, workoutOwnerLabels, workoutPlanErrorMessage, workoutStatusLabels } from './workoutPlanMessages';

export function StudentWorkoutPlansScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const currentQuery = useCurrentWorkoutPlan(studentId);
  const listQuery = useWorkoutPlans(studentId);
  const current = currentQuery.data;
  const currentVersionQuery = useWorkoutPlanVersion(studentId, current?.plan.id ?? '', current?.currentVersion?.id ?? '');
  const plans = useMemo(() => listQuery.data?.pages.flatMap((page) => page.items) ?? [], [listQuery.data]);
  const otherPlans = plans.filter((plan) => plan.id !== current?.plan.id);

  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem kế hoạch tập luyện." />;

  return <ScrollView testID="student-workout-plans-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Kế hoạch tập luyện</Text>
    <WorkoutCard testID="current-plan-section">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Kế hoạch đang áp dụng</Text>
      {currentQuery.isPending ? <WorkoutState busy title="Đang tải kế hoạch hiện tại" message="Vui lòng chờ trong giây lát." /> : null}
      {currentQuery.isError && !isCurrentPlanMissing(currentQuery.error) ? <WorkoutState testID="current-plan-error" title="Không thể tải kế hoạch hiện tại" message={workoutPlanErrorMessage(currentQuery.error)} onRetry={() => currentQuery.refetch()} /> : null}
      {currentQuery.isError && isCurrentPlanMissing(currentQuery.error) ? <WorkoutState testID="current-plan-empty" title="Chưa có kế hoạch đang áp dụng" message="Bạn có thể mở một bản nháp, tiếp tục kế hoạch đã bàn giao hoặc tạo kế hoạch mới." /> : null}
      {current ? <>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{current.plan.name}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutStatusLabels[current.plan.status]} · {workoutOwnerLabels[current.plan.decisionOwnerType]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {current.currentVersion?.versionNumber ?? '—'}{current.currentVersion?.effectiveFrom ? ` · hiệu lực ${new Date(current.currentVersion.effectiveFrom).toLocaleString('vi-VN')}` : ''}</Text>
        {currentVersionQuery.isPending ? <Text style={[styles.body, { color: theme.textSecondary }]}>Đang tải tóm tắt buổi tập…</Text> : null}
        {currentVersionQuery.isError ? <WorkoutState testID="current-plan-content-error" title="Không thể tải tóm tắt buổi tập" message={workoutPlanErrorMessage(currentVersionQuery.error)} onRetry={() => currentVersionQuery.refetch()} /> : null}
        {currentVersionQuery.data ? <Text style={[styles.body, { color: theme.textSecondary }]}>{currentVersionQuery.data.sessions.length} buổi trong phiên bản hiện tại</Text> : null}
        <WorkoutButton label="Xem kế hoạch hiện tại" onPress={() => router.push(`/workout-plans/${current.plan.id}` as never)} />
      </> : null}
    </WorkoutCard>

    <WorkoutButton testID="create-workout-plan-button" label="Tạo kế hoạch mới" onPress={() => router.push('/workout-plans/new' as never)} hint="Backend sẽ kiểm tra chế độ SELF_DIRECTED khi lưu" />

    <WorkoutCard testID="workout-plan-list">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Kế hoạch khác và lịch sử</Text>
      {listQuery.isPending ? <WorkoutState busy title="Đang tải danh sách" message="Vui lòng chờ trong giây lát." /> : null}
      {listQuery.isError ? <WorkoutState testID="plan-list-error" title="Không thể tải danh sách" message={workoutPlanErrorMessage(listQuery.error)} onRetry={() => listQuery.refetch()} /> : null}
      {!listQuery.isPending && !listQuery.isError && otherPlans.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có kế hoạch khác.</Text> : null}
      {otherPlans.map((plan) => <WorkoutCard key={plan.id} testID={`workout-plan-${plan.id}`}>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{plan.name}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutStatusLabels[plan.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{workoutOwnerLabels[plan.decisionOwnerType]} · {plan.readContext === 'HISTORICAL' ? 'Bản ghi lịch sử' : 'Kế hoạch hiện tại'}</Text>
        <WorkoutButton label={`Xem ${plan.name}`} secondary onPress={() => router.push(`/workout-plans/${plan.id}` as never)} />
      </WorkoutCard>)}
      {listQuery.hasNextPage ? <WorkoutButton label={listQuery.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm kế hoạch'} busy={listQuery.isFetchingNextPage} disabled={listQuery.isFetchingNextPage} secondary onPress={() => listQuery.fetchNextPage()} /> : null}
    </WorkoutCard>
  </ScrollView>;
}
