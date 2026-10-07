import React, { useEffect, useMemo } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { removeStudentWorkoutPlanCache } from './workoutPlanCache';
import { useWorkoutPlans } from './workoutPlanQueries';
import { isWorkoutAuthorityLoss, workoutPlanErrorMessage, workoutStatusLabels } from './workoutPlanMessages';

export function TrainerProgramScreen({ relationshipId }: { relationshipId: string }) {
  const { activeCapability, user } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship ?? null;
  const studentId = relationship?.studentId ?? '';
  const planPermission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const historyPermission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN_HISTORY');
  const canView = planPermission?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(planPermission.accessLevel ?? '');
  const canManage = planPermission?.state === 'ALLOWED' && planPermission.accessLevel === 'MANAGE';
  const canViewHistory = historyPermission?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(historyPermission.accessLevel ?? '');
  const relationshipCurrent = relationship?.status === 'ACTIVE';
  const plansQuery = useWorkoutPlans(studentId, Boolean(activeCapability === 'TRAINER' && relationship?.trainerId === user?.id && relationshipCurrent && canView), studentId);
  const plans = useMemo(() => plansQuery.data?.pages.flatMap((page) => page.items) ?? [], [plansQuery.data]);

  useEffect(() => {
    if (studentId && plansQuery.isError && isWorkoutAuthorityLoss(plansQuery.error)) {
      removeStudentWorkoutPlanCache(client, studentId);
      router.replace('/coaching' as never);
    }
  }, [client, plansQuery.error, plansQuery.isError, router, studentId]);

  useEffect(() => {
    if (studentId && relationship && relationship.status !== 'ACTIVE') {
      removeStudentWorkoutPlanCache(client, studentId);
    }
  }, [client, relationship, studentId]);

  if (activeCapability !== 'TRAINER') return <CoachingState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ Huấn luyện viên để mở chương trình." />;
  if (relationshipQuery.isPending || sharingQuery.isPending) return <CoachingState busy title="Đang tải chương trình" message="Vui lòng chờ trong giây lát." />;
  if (relationshipQuery.isError || !relationship || relationship.trainerId !== user?.id) return <CoachingState title="Không thể mở chương trình" message="Quan hệ không tồn tại hoặc bạn không còn quyền truy cập." onRetry={() => relationshipQuery.refetch()} />;
  if (sharingQuery.isError) return <CoachingState title="Chưa thể kiểm tra quyền" message={workoutPlanErrorMessage(sharingQuery.error)} onRetry={() => sharingQuery.refetch()} />;
  if (!['ACTIVE', 'PAUSED'].includes(relationship.status)) return <CoachingState title="Quan hệ không còn hiện tại" message="Dữ liệu kế hoạch đã được xóa khỏi bộ nhớ đệm của Huấn luyện viên." />;

  return <ScrollView testID="trainer-program-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chương trình của {relationship.counterparty.displayName}</Text>
    <CoachingCard testID="program-authority-card">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Quyền chương trình</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch hiện tại: {canManage ? 'Quản lý' : canView ? 'Chỉ xem' : 'Không được chia sẻ'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Lịch sử kế hoạch: {canViewHistory ? 'Được xem' : 'Không được chia sẻ'}</Text>
      {relationship.status === 'PAUSED' ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Quan hệ đang tạm dừng. Backend sẽ xác minh lại quyền hiện tại trước mọi thao tác.</Text> : null}
    </CoachingCard>
    {!canView ? <CoachingState testID="program-denied" title="Chưa có quyền xem kế hoạch" message="Học viên cần cấp ít nhất quyền Xem cho Kế hoạch tập luyện." /> : null}
    {relationship.status === 'PAUSED' ? <CoachingState testID="program-paused" title="Quan hệ đang tạm dừng" message="Quyền hiện tại không có hiệu lực khi quan hệ tạm dừng. Kế hoạch không được tải trước." /> : null}
    {canView && relationshipCurrent && plansQuery.isPending ? <CoachingState busy title="Đang tải kế hoạch" message="Vui lòng chờ trong giây lát." /> : null}
    {canView && relationshipCurrent && plansQuery.isError ? <CoachingState testID="program-error" title="Không thể tải kế hoạch" message={workoutPlanErrorMessage(plansQuery.error)} onRetry={() => plansQuery.refetch()} /> : null}
    {canView && relationshipCurrent && !plansQuery.isPending && !plansQuery.isError && plans.length === 0 ? <CoachingState testID="program-empty" title="Chưa có kế hoạch" message={canManage ? 'Bạn có thể tạo bản nháp đầu tiên cho học viên.' : 'Học viên chưa có kế hoạch được phép xem.'} /> : null}
    {canView && relationshipCurrent ? plans.map((plan) => <CoachingCard key={plan.id} testID={`program-plan-${plan.id}`}>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{plan.name}</Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutStatusLabels[plan.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{plan.readContext === 'HISTORICAL' ? 'Bản ghi lịch sử' : 'Kế hoạch hiện tại'} · {plan.decisionOwnerType === 'TRAINER' ? 'Huấn luyện viên sở hữu quyết định' : 'Học viên sở hữu quyết định'}</Text>
      <CoachingButton label="Xem kế hoạch" secondary onPress={() => router.push(`/students/${studentId}/workout-plans/${plan.id}?relationshipId=${relationshipId}` as never)} />
    </CoachingCard>) : null}
    {canManage && relationship.status === 'ACTIVE' ? <CoachingButton testID="create-plan-button" label="Tạo kế hoạch mới" onPress={() => router.push(`/coaching/${relationshipId}/workout-plans/new` as never)} /> : null}
    {plansQuery.hasNextPage ? <CoachingButton label={plansQuery.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm kế hoạch'} busy={plansQuery.isFetchingNextPage} disabled={plansQuery.isFetchingNextPage} secondary onPress={() => plansQuery.fetchNextPage()} /> : null}
    <CoachingButton label="Quay lại quan hệ" secondary onPress={() => router.back()} />
  </ScrollView>;
}
