import { SafeAreaView } from 'react-native-safe-area-context';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, AccessibilityInfo, Modal, ScrollView, Text, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { WorkoutPlanStatus } from '@/types/workoutPlan';
import { workoutStyles as styles } from './components/WorkoutUi';
import { WorkoutPlanVersionContent } from './components/WorkoutPlanVersionContent';
import { AiWorkoutAdjustmentModal, ExercisePrescriptionOption } from './components/AiWorkoutAdjustmentModal';
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
  const currentVersionId = canViewCurrent ? detailQuery.data?.currentVersion?.id ?? '' : '';
  const currentVersionQuery = useWorkoutPlanVersion(
    studentId,
    planId,
    currentVersionId,
    Boolean(currentVersionId && canViewCurrent && currentVersionId !== selectedId)
  );
  const [pendingTarget, setPendingTarget] = useState<WorkoutPlanStatus | 'ACTIVATE' | null>(null);
  const [isAdjustmentModalVisible, setIsAdjustmentModalVisible] = useState(false);

  const currentPrescriptions: ExercisePrescriptionOption[] = useMemo(() => {
    const currentData = selectedId === currentVersionId ? versionQuery.data : currentVersionQuery.data;
    if (!currentData?.sessions) return [];
    return currentData.sessions.flatMap((s) =>
      s.prescriptions.map((p) => ({
        id: p.id,
        exerciseName: `${p.exercise.exerciseName || 'Bài tập'} (Ngày ${s.dayNumber})`,
        dayNumber: s.dayNumber,
        targetLoad: p.targetLoad,
        loadUnit: null,
        targetSets: p.targetSets,
        targetRepsMin: p.targetRepsMin,
        targetRepsMax: p.targetRepsMax,
        durationSeconds: p.durationSeconds,
      }))
    );
  }, [selectedId, currentVersionId, versionQuery.data, currentVersionQuery.data]);
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
      if (!plan) throw new Error('Không tìm thấy kế hoạch tập');
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

  if (activeCapability !== 'TRAINER') return <AppCard title="Chỉ dành cho Huấn luyện viên" message="Hãy chuyển sang hồ sơ Huấn luyện viên để mở kế hoạch." state="info" />;
  if (sharingQuery.isPending) return <AppCard title="Đang kiểm tra quyền" message="Vui lòng chờ trong giây lát." state="loading" />;
  if (sharingQuery.isError) return <AppCard title="Không thể xác minh quyền" message={workoutPlanErrorMessage(sharingQuery.error)} onRetry={() => sharingQuery.refetch()} state="error" />;
  if (!canReadAny) return <AppCard testID="plan-detail-denied" title="Không có quyền xem kế hoạch" message="Cần quyền Xem cho kế hoạch hiện tại hoặc lịch sử kế hoạch." state="info" />;
  if (detailQuery.isPending) return <AppCard title="Đang tải kế hoạch" message="Vui lòng chờ trong giây lát." state="loading" />;
  if (detailQuery.isError || !plan || plan.studentId !== studentId) return <AppCard testID="plan-detail-error" title="Không thể mở kế hoạch" message={workoutPlanErrorMessage(detailQuery.error)} onRetry={() => detailQuery.refetch()} state="error" />;

  return <SafeAreaView style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="workout-plan-detail-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]} automaticallyAdjustKeyboardInsets>
    <View style={{ flexDirection: 'row', gap: spacing.md, alignItems: 'center' }}><AppIcon name="workout" color={theme.primary} /><Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary, flex: 1 }]}>{plan.name}</Text></View>
    <AppCard>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutStatusLabels[plan.status]} · {workoutOwnerLabels[plan.decisionOwnerType]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{plan.description || 'Không có mô tả'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản aggregate {plan.aggregateVersion} · {plan.readContext === 'HISTORICAL' ? 'Lịch sử' : 'Hiện tại'}</Text>
      {plan.readContext === 'HISTORICAL' || plan.status === 'ARCHIVED' ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Bản ghi này chỉ để xem và không thể chỉnh sửa.</Text> : null}
    </AppCard>
    {canManage && plan.status === 'DRAFT' ? <View style={styles.row}><AppButton testID="edit-draft-button" label="Chỉnh sửa bản nháp" onPress={() => router.push(`/students/${studentId}/workout-plans/${planId}/builder?relationshipId=${relationshipId}&mode=draft` as never)} icon="edit" variant="secondary" /><AppButton testID="activate-plan-button" label="Kích hoạt" onPress={() => openCommand('ACTIVATE')} icon="check" /></View> : null}
    {canManage && ['ACTIVE', 'PAUSED'].includes(plan.status) && detailQuery.data.currentVersion ? <AppButton testID="publish-version-button" label="Tạo phiên bản chiến lược mới" onPress={() => router.push(`/students/${studentId}/workout-plans/${planId}/builder?relationshipId=${relationshipId}&mode=publish&versionId=${detailQuery.data.currentVersion?.id}` as never)} icon="history" variant="secondary" /> : null}
    {canManage && detailQuery.data.currentVersion ? <AppButton testID="trainer-ai-adjustment-button" label="Đề xuất điều chỉnh bài tập (AI)" onPress={() => setIsAdjustmentModalVisible(true)} variant="secondary" icon="knowledge" /> : null}
    {canManage ? <View style={styles.row}>{transitions[plan.status].map((target) => <AppButton key={target} label={actionLabels[target] ?? workoutStatusLabels[target]} onPress={() => openCommand(target)} variant="secondary" tone={target === 'ARCHIVED' ? 'danger' : 'brand'} />)}</View> : null}
    {!canManage && plan.readContext === 'CURRENT' ? <AppCard testID="plan-read-only" title="Chỉ xem" message="CONTRIBUTE không phải quyền tác giả. Cần quyền Quản lý và quyền quyết định hiện tại để sửa kế hoạch." state="info" /> : null}
    {mutation.isError ? <AppCard testID="plan-command-error" title="Chưa thể cập nhật kế hoạch" message={workoutPlanErrorMessage(mutation.error)} state="error" /> : null}

    {canViewHistory ? <AppCard testID="plan-version-history">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử phiên bản</Text>
      {versionsQuery.isPending ? <AppCard title="Đang tải lịch sử" message="Vui lòng chờ trong giây lát." state="loading" /> : null}
      {versionsQuery.isError ? <AppCard title="Không thể tải lịch sử" message={workoutPlanErrorMessage(versionsQuery.error)} onRetry={() => versionsQuery.refetch()} state="error" /> : null}
      {!versionsQuery.isPending && !versionsQuery.isError && versions.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bản nháp chưa có phiên bản được phát hành.</Text> : null}
      {versions.map((version) => <AppButton key={version.id} label={`Phiên bản ${version.versionNumber}${version.current ? ' · hiện tại' : ''}`} disabled={version.id === selectedId} onPress={() => setSelectedVersionId(version.id)} variant="secondary" />)}
      {versionsQuery.hasNextPage ? <AppButton label="Tải thêm phiên bản" loading={versionsQuery.isFetchingNextPage} disabled={versionsQuery.isFetchingNextPage} onPress={() => versionsQuery.fetchNextPage()} variant="secondary" icon="history" /> : null}
    </AppCard> : null}
    {selectedId ? <WorkoutPlanVersionContent testID="selected-version-detail" title={canViewHistory ? 'Nội dung phiên bản đã chọn' : 'Nội dung phiên bản hiện tại'} detail={versionQuery.data} isPending={versionQuery.isPending} isError={versionQuery.isError} error={versionQuery.error} onRetry={() => versionQuery.refetch()} /> : null}
    <AppButton label="Quay lại chương trình" onPress={() => router.back()} variant="secondary" icon="back" />

    <AiWorkoutAdjustmentModal
      visible={isAdjustmentModalVisible}
      studentId={studentId}
      isTrainer={true}
      initialScope="TEMPLATE_OR_FUTURE_OCCURRENCES"
      planContext={
        detailQuery.data.currentVersion
          ? {
              planId: plan.id,
              planVersionNumber: detailQuery.data.currentVersion.versionNumber,
              planAggregateVersion: plan.aggregateVersion,
              planName: plan.name,
              prescriptions: currentPrescriptions,
            }
          : undefined
      }
      onClose={() => setIsAdjustmentModalVisible(false)}
      onAdjustmentApplied={async () => {
        setIsAdjustmentModalVisible(false);
        await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        await detailQuery.refetch();
        await versionQuery.refetch();
        if (currentVersionId && currentVersionId !== selectedId) {
          await currentVersionQuery.refetch();
        }
      }}
    />

    <Modal visible={pendingTarget !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeCommand}>
      <SafeAreaView style={{ flex: 1, backgroundColor: theme.canvas }}><KeyboardAvoidingView style={[detailStyles.backdrop, { backgroundColor: theme.canvas }]} behavior={Platform.OS === 'ios' ? 'padding' : undefined}><ScrollView style={detailStyles.modalScroll} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets><AppCard testID="plan-command-confirmation"><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận thay đổi</Text><Text style={[styles.body, { color: theme.textSecondary }]}>{pendingTarget === 'ACTIVATE' ? 'Kích hoạt sẽ khóa phiên bản đầu tiên. Kế hoạch đang áp dụng khác của Học viên có thể gây xung đột.' : 'Hệ thống sẽ kiểm tra lại quyền, trạng thái và phiên bản mới nhất trước khi cập nhật.'}</Text><View style={styles.row}><AppButton label="Quay lại" disabled={mutation.isPending} onPress={closeCommand} variant="secondary" icon="back" /><AppButton testID="confirm-plan-command" label={mutation.isPending ? 'Đang xử lý' : 'Xác nhận'} loading={mutation.isPending} disabled={mutation.isPending} onPress={() => pendingTarget && mutation.mutate(pendingTarget)} variant={pendingTarget === 'ARCHIVED' ? 'danger' : 'primary'} icon="check" /></View></AppCard></ScrollView></KeyboardAvoidingView></SafeAreaView>
    </Modal>
  </ScrollView></SafeAreaView>;
}

const detailStyles = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: 'center', padding: spacing.lg },
  modalScroll: { flexGrow: 0, width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' },
});
