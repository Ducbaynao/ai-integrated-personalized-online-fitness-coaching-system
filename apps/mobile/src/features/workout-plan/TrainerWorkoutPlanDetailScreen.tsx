import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Modal, ScrollView, Text, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { WorkoutPlanStatus } from '@/types/workoutPlan';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from './components/WorkoutUi';
import { WorkoutPlanVersionContent } from './components/WorkoutPlanVersionContent';
import { removeStudentWorkoutPlanCache } from './workoutPlanCache';
import { isWorkoutAuthorityLoss, isWorkoutStaleConflict, workoutOwnerLabels, workoutPlanErrorMessage, workoutStatusLabels } from './workoutPlanMessages';
import { useWorkoutPlan, useWorkoutPlanVersion, useWorkoutPlanVersions, workoutPlanQueryKeys } from './workoutPlanQueries';

const transitions: Record<WorkoutPlanStatus, WorkoutPlanStatus[]> = {
  DRAFT: ['ARCHIVED'], ACTIVE: ['PAUSED', 'COMPLETED', 'ARCHIVED'], PAUSED: ['ACTIVE', 'COMPLETED', 'ARCHIVED'], COMPLETED: ['ARCHIVED'], ARCHIVED: [],
};
const actionLabels: Partial<Record<WorkoutPlanStatus, string>> = { ACTIVE: 'Tiếp tục kế hoạch', PAUSED: 'Tạm dừng kế hoạch', COMPLETED: 'Hoàn thành kế hoạch', ARCHIVED: 'Lưu trữ kế hoạch' };

export function TrainerWorkoutPlanDetailScreen({ studentId, planId, relationshipId }: { studentId: string; planId: string; relationshipId: string }) {
  const { activeCapability, user } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const sharingQuery = useSharingSummary(relationshipId);
  const currentPermission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const historyPermission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN_HISTORY');
  const relationshipCurrent = sharingQuery.data?.relationshipStatus === 'ACTIVE';
  const canViewCurrent = relationshipCurrent && currentPermission?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(currentPermission.accessLevel ?? '');
  const canViewHistory = historyPermission?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(historyPermission.accessLevel ?? '');
  const canReadAny = canViewCurrent || canViewHistory;
  const detailQuery = useWorkoutPlan(studentId, planId, canReadAny);
  const versionsQuery = useWorkoutPlanVersions(studentId, planId, canViewHistory);
  const versions = useMemo(() => versionsQuery.data?.pages.flatMap((page) => page.items) ?? [], [versionsQuery.data]);
  const [selectedVersionId, setSelectedVersionId] = useState('');
  const selectedId = selectedVersionId || (canViewCurrent ? detailQuery.data?.currentVersion?.id : '') || versions[0]?.id || '';
  const versionQuery = useWorkoutPlanVersion(studentId, planId, selectedId, Boolean(selectedId && (canViewCurrent || canViewHistory)));
  const [pendingTarget, setPendingTarget] = useState<WorkoutPlanStatus | 'ACTIVATE' | null>(null);
  const commandKey = useRef<string | null>(null);
  const plan = detailQuery.data?.plan;
  const canManage = relationshipCurrent && currentPermission?.state === 'ALLOWED' && currentPermission.accessLevel === 'MANAGE'
    && plan?.readContext === 'CURRENT' && plan.decisionOwnerType === 'TRAINER' && plan.decisionOwnerId === user?.id;
  const authorityError = sharingQuery.error ?? detailQuery.error ?? versionsQuery.error ?? versionQuery.error;

  useEffect(() => {
    if (isWorkoutAuthorityLoss(authorityError)) {
      removeStudentWorkoutPlanCache(client, studentId);
      router.replace('/coaching' as never);
    }
  }, [authorityError, client, router, studentId]);

  const mutation = useMutation({
    mutationFn: async (target: WorkoutPlanStatus | 'ACTIVATE') => {
      if (!plan) throw new Error('Missing workout plan');
      const key = commandKey.current ?? createCommandKey(); commandKey.current = key;
      return target === 'ACTIVATE' ? workoutPlanApi.activate(plan.id, plan.aggregateVersion, key) : workoutPlanApi.transition(plan.id, plan.aggregateVersion, target, null, key);
    },
    onSuccess: async () => {
      const announcement = pendingTarget === 'ACTIVATE' ? 'Kế hoạch đã được kích hoạt.' : 'Trạng thái kế hoạch đã được cập nhật.';
      setPendingTarget(null); commandKey.current = null;
      await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
      AccessibilityInfo.announceForAccessibility(announcement);
    },
    onError: async (error) => {
      if (isWorkoutAuthorityLoss(error)) {
        removeStudentWorkoutPlanCache(client, studentId); setPendingTarget(null); commandKey.current = null;
        router.replace('/coaching' as never);
      } else if (isWorkoutStaleConflict(error)) {
        setPendingTarget(null); commandKey.current = null;
        await detailQuery.refetch();
      }
    },
  });
  const openCommand = (target: WorkoutPlanStatus | 'ACTIVATE') => { commandKey.current = createCommandKey(); setPendingTarget(target); };
  const closeCommand = () => { if (!mutation.isPending) { setPendingTarget(null); commandKey.current = null; } };

  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho Huấn luyện viên" message="Hãy chuyển sang hồ sơ Huấn luyện viên để mở kế hoạch." />;
  if (sharingQuery.isPending) return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ trong giây lát." />;
  if (sharingQuery.isError) return <WorkoutState title="Không thể xác minh quyền" message={workoutPlanErrorMessage(sharingQuery.error)} onRetry={() => sharingQuery.refetch()} />;
  if (!canReadAny) return <WorkoutState testID="plan-detail-denied" title="Không có quyền xem kế hoạch" message="Cần quyền Xem cho kế hoạch hiện tại hoặc lịch sử kế hoạch." />;
  if (detailQuery.isPending) return <WorkoutState busy title="Đang tải kế hoạch" message="Vui lòng chờ trong giây lát." />;
  if (detailQuery.isError || !plan || plan.studentId !== studentId) return <WorkoutState testID="plan-detail-error" title="Không thể mở kế hoạch" message={workoutPlanErrorMessage(detailQuery.error)} onRetry={() => detailQuery.refetch()} />;

  return <ScrollView testID="workout-plan-detail-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>{plan.name}</Text>
    <WorkoutCard>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutStatusLabels[plan.status]} · {workoutOwnerLabels[plan.decisionOwnerType]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{plan.description || 'Không có mô tả'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản aggregate {plan.aggregateVersion} · {plan.readContext === 'HISTORICAL' ? 'Lịch sử' : 'Hiện tại'}</Text>
      {plan.readContext === 'HISTORICAL' || plan.status === 'ARCHIVED' ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Bản ghi này chỉ để xem và không thể chỉnh sửa.</Text> : null}
    </WorkoutCard>
    {canManage && plan.status === 'DRAFT' ? <View style={styles.row}><WorkoutButton testID="edit-draft-button" label="Chỉnh sửa bản nháp" onPress={() => router.push(`/students/${studentId}/workout-plans/${planId}/builder?relationshipId=${relationshipId}&mode=draft` as never)} /><WorkoutButton testID="activate-plan-button" label="Kích hoạt" onPress={() => openCommand('ACTIVATE')} /></View> : null}
    {canManage && ['ACTIVE', 'PAUSED'].includes(plan.status) && detailQuery.data.currentVersion ? <WorkoutButton testID="publish-version-button" label="Tạo phiên bản chiến lược mới" onPress={() => router.push(`/students/${studentId}/workout-plans/${planId}/builder?relationshipId=${relationshipId}&mode=publish&versionId=${detailQuery.data.currentVersion?.id}` as never)} /> : null}
    {canManage ? <View style={styles.row}>{transitions[plan.status].map((target) => <WorkoutButton key={target} label={actionLabels[target] ?? workoutStatusLabels[target]} danger={target === 'ARCHIVED'} secondary onPress={() => openCommand(target)} />)}</View> : null}
    {!canManage && plan.readContext === 'CURRENT' ? <WorkoutState testID="plan-read-only" title="Chỉ xem" message="CONTRIBUTE không phải quyền tác giả. Cần quyền Quản lý và quyền quyết định hiện tại để sửa kế hoạch." /> : null}
    {mutation.isError ? <WorkoutState testID="plan-command-error" title="Chưa thể cập nhật kế hoạch" message={workoutPlanErrorMessage(mutation.error)} /> : null}

    {canViewHistory ? <WorkoutCard testID="plan-version-history">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử phiên bản</Text>
      {versionsQuery.isPending ? <WorkoutState busy title="Đang tải lịch sử" message="Vui lòng chờ trong giây lát." /> : null}
      {versionsQuery.isError ? <WorkoutState title="Không thể tải lịch sử" message={workoutPlanErrorMessage(versionsQuery.error)} onRetry={() => versionsQuery.refetch()} /> : null}
      {!versionsQuery.isPending && !versionsQuery.isError && versions.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bản nháp chưa có phiên bản được phát hành.</Text> : null}
      {versions.map((version) => <WorkoutButton key={version.id} label={`Phiên bản ${version.versionNumber}${version.current ? ' · hiện tại' : ''}`} secondary disabled={version.id === selectedId} onPress={() => setSelectedVersionId(version.id)} />)}
      {versionsQuery.hasNextPage ? <WorkoutButton label="Tải thêm phiên bản" secondary busy={versionsQuery.isFetchingNextPage} disabled={versionsQuery.isFetchingNextPage} onPress={() => versionsQuery.fetchNextPage()} /> : null}
    </WorkoutCard> : null}
    {selectedId ? <WorkoutPlanVersionContent testID="selected-version-detail" title={canViewHistory ? 'Nội dung phiên bản đã chọn' : 'Nội dung phiên bản hiện tại'} detail={versionQuery.data} isPending={versionQuery.isPending} isError={versionQuery.isError} error={versionQuery.error} onRetry={() => versionQuery.refetch()} /> : null}
    <WorkoutButton label="Quay lại chương trình" secondary onPress={() => router.back()} />

    <Modal visible={pendingTarget !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeCommand}>
      <View style={{ flex: 1, backgroundColor: '#00000066', justifyContent: 'center', padding: spacing.lg }}><WorkoutCard testID="plan-command-confirmation"><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận thay đổi</Text><Text style={[styles.body, { color: theme.textSecondary }]}>{pendingTarget === 'ACTIVATE' ? 'Kích hoạt sẽ khóa phiên bản đầu tiên. Kế hoạch đang áp dụng khác của Học viên có thể gây xung đột.' : 'Backend sẽ kiểm tra lại quyền, trạng thái và phiên bản mới nhất trước khi cập nhật.'}</Text><View style={styles.row}><WorkoutButton label="Quay lại" secondary disabled={mutation.isPending} onPress={closeCommand} /><WorkoutButton testID="confirm-plan-command" label={mutation.isPending ? 'Đang xử lý' : 'Xác nhận'} busy={mutation.isPending} disabled={mutation.isPending} danger={pendingTarget === 'ARCHIVED'} onPress={() => pendingTarget && mutation.mutate(pendingTarget)} /></View></WorkoutCard></View>
    </Modal>
  </ScrollView>;
}
