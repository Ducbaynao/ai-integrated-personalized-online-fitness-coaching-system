import { SafeAreaView } from 'react-native-safe-area-context';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import { formatInstantVietnam } from '@/localization/instantDisplay';
import React, { useMemo, useRef, useState } from 'react';
import { KeyboardAvoidingView, Platform, StyleSheet, AccessibilityInfo, Modal, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { createCommandKey } from '@/services/coachingApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { WorkoutPlanStatus } from '@/types/workoutPlan';
import { TrainerWorkoutPlanDetailScreen } from './TrainerWorkoutPlanDetailScreen';
import { WorkoutPlanVersionContent } from './components/WorkoutPlanVersionContent';
import { AiWorkoutAdjustmentModal, ExercisePrescriptionOption } from './components/AiWorkoutAdjustmentModal';
import { workoutStyles as styles } from './components/WorkoutUi';
import { useWorkoutPlan, useWorkoutPlanVersion, workoutPlanQueryKeys } from './workoutPlanQueries';
import { isWorkoutStaleConflict, workoutOwnerLabels, workoutPlanErrorMessage, workoutStatusLabels } from './workoutPlanMessages';

type PendingCommand = 'ACTIVATE' | WorkoutPlanStatus;
const labels: Partial<Record<PendingCommand, string>> = { ACTIVATE: 'Kích hoạt kế hoạch', ACTIVE: 'Tiếp tục kế hoạch', PAUSED: 'Tạm dừng kế hoạch', COMPLETED: 'Hoàn thành kế hoạch', ARCHIVED: 'Lưu trữ kế hoạch' };

function actions(status: WorkoutPlanStatus, studentOwned: boolean): WorkoutPlanStatus[] {
  if (status === 'DRAFT') return ['ARCHIVED'];
  if (status === 'ACTIVE') return studentOwned ? ['PAUSED', 'COMPLETED', 'ARCHIVED'] : ['COMPLETED', 'ARCHIVED'];
  if (status === 'PAUSED') return studentOwned ? ['ACTIVE', 'COMPLETED', 'ARCHIVED'] : ['COMPLETED', 'ARCHIVED'];
  if (status === 'COMPLETED') return ['ARCHIVED'];
  return [];
}

interface WorkoutPlanDetailScreenProps {
  planId: string;
  studentId?: string;
  relationshipId?: string;
}

export function WorkoutPlanDetailScreen({ planId, studentId, relationshipId }: WorkoutPlanDetailScreenProps) {
  if (studentId && relationshipId) {
    return <TrainerWorkoutPlanDetailScreen planId={planId} studentId={studentId} relationshipId={relationshipId} />;
  }
  return <StudentWorkoutPlanDetailScreen planId={planId} />;
}

function StudentWorkoutPlanDetailScreen({ planId }: { planId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const query = useWorkoutPlan(studentId, planId);
  const plan = query.data?.plan;
  const currentVersion = query.data?.currentVersion;
  const versionQuery = useWorkoutPlanVersion(studentId, planId, currentVersion?.id ?? '');
  const [isAdjustmentModalVisible, setIsAdjustmentModalVisible] = useState(false);

  const prescriptions: ExercisePrescriptionOption[] = useMemo(() => {
    if (!versionQuery.data?.sessions) return [];
    return versionQuery.data.sessions.flatMap((s) =>
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
  }, [versionQuery.data]);
  const [pendingCommand, setPendingCommand] = useState<PendingCommand | null>(null);
  const [successorOpen, setSuccessorOpen] = useState(false);
  const [successorName, setSuccessorName] = useState('');
  const commandKey = useRef<string | null>(null);
  const successorKey = useRef<string | null>(null);
  const studentOwned = plan?.decisionOwnerType === 'STUDENT' && plan.decisionOwnerId === studentId;
  const mutableContext = plan?.readContext === 'CURRENT' && plan.status !== 'ARCHIVED';

  const commandMutation = useMutation({
    mutationFn: async (target: PendingCommand) => {
      if (!plan) throw new Error('Không tìm thấy kế hoạch');
      const key = commandKey.current ?? createCommandKey(); commandKey.current = key;
      return target === 'ACTIVATE' ? workoutPlanApi.activate(plan.id, plan.aggregateVersion, key) : workoutPlanApi.transition(plan.id, plan.aggregateVersion, target, null, key);
    },
    onSuccess: async () => {
      const announcement = pendingCommand === 'ACTIVATE' ? 'Kế hoạch đã được kích hoạt.' : 'Trạng thái kế hoạch đã được cập nhật.';
      setPendingCommand(null); commandKey.current = null;
      await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
      AccessibilityInfo.announceForAccessibility(announcement);
    },
    onError: async (error) => {
      if (isWorkoutStaleConflict(error)) {
        setPendingCommand(null); commandKey.current = null;
        await query.refetch();
      }
    },
  });

  const successorMutation = useMutation({
    mutationFn: async () => {
      if (!plan || !currentVersion) throw new Error('Không tìm thấy nguồn dữ liệu');
      const key = successorKey.current ?? createCommandKey(); successorKey.current = key;
      return workoutPlanApi.successor(plan.id, currentVersion.id, successorName, key);
    },
    onSuccess: async (result) => {
      setSuccessorOpen(false); successorKey.current = null;
      await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
      AccessibilityInfo.announceForAccessibility('Đã tạo kế hoạch kế nhiệm do Học viên sở hữu.');
      router.replace(`/workout-plans/${result.planId}` as never);
    },
  });

  const openCommand = (target: PendingCommand) => { commandKey.current = createCommandKey(); setPendingCommand(target); };
  const closeCommand = () => { if (!commandMutation.isPending) { commandKey.current = null; setPendingCommand(null); } };
  const openSuccessor = () => { successorKey.current = createCommandKey(); setSuccessorName(`${plan?.name ?? 'Kế hoạch'} - tự tập`); setSuccessorOpen(true); };
  const closeSuccessor = () => { if (!successorMutation.isPending) { successorKey.current = null; setSuccessorOpen(false); } };

  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) return <AppCard title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem kế hoạch." state="info" />;
  if (query.isPending) return <AppCard title="Đang tải kế hoạch" message="Vui lòng chờ trong giây lát." state="loading" />;
  if (query.isError || !plan) return <AppCard testID="plan-detail-error" title="Không thể mở kế hoạch" message={workoutPlanErrorMessage(query.error)} onRetry={() => query.refetch()} state="error" />;

  return <SafeAreaView style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="workout-plan-detail-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]} automaticallyAdjustKeyboardInsets>
    <View style={{ flexDirection: 'row', gap: spacing.md, alignItems: 'center' }}><AppIcon name="workout" color={theme.primary} /><Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary, flex: 1 }]}>{plan.name}</Text></View>
    <AppCard>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutStatusLabels[plan.status]} · {workoutOwnerLabels[plan.decisionOwnerType]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{plan.description || 'Không có mô tả'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản aggregate {plan.aggregateVersion} · {plan.readContext === 'CURRENT' ? 'Hiện tại' : 'Lịch sử'}</Text>
      {currentVersion ? <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản nội dung {currentVersion.versionNumber} · hiệu lực {(formatInstantVietnam(currentVersion.effectiveFrom) ?? 'Chưa rõ thời điểm')}</Text> : <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có phiên bản được kích hoạt.</Text>}
      {plan.basedOnPlanId ? <Text style={[styles.body, { color: theme.textSecondary }]}>Kế nhiệm từ kế hoạch {plan.basedOnPlanId}</Text> : null}
      {!mutableContext ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Đây là bản ghi lịch sử chỉ để xem.</Text> : null}
    </AppCard>

    {currentVersion ? <WorkoutPlanVersionContent testID="plan-session-content" title="Nội dung phiên bản hiện tại" detail={versionQuery.data} isPending={versionQuery.isPending} isError={versionQuery.isError} error={versionQuery.error} onRetry={() => versionQuery.refetch()} /> : null}

    {plan.status === 'ACTIVE' && plan.readContext === 'CURRENT' && currentVersion?.lockedAt && currentVersion.effectiveUntil === null ? <AppButton testID="open-direct-scheduling" label="Tự lên lịch từ kế hoạch này" onPress={() => router.push(`/workout-plans/${plan.id}/schedule` as never)} accessibilityHint="Chọn buổi từ phiên bản đã xuất bản; lịch chỉ được tạo sau khi bạn xác nhận cả đợt" icon="calendar" /> : null}
    {plan.status === 'ACTIVE' && plan.readContext === 'CURRENT' && currentVersion?.lockedAt && currentVersion.effectiveUntil === null ? <AppButton testID="open-recurring-scheduling" label="Tạo lịch tập lặp" onPress={() => router.push(`/workout-plans/${plan.id}/series` as never)} accessibilityHint="Chọn buổi và xác nhận series lặp theo toàn bộ block tuần của phiên bản đã xuất bản" variant="secondary" icon="calendar" /> : null}

    {currentVersion ? <AppButton testID="ai-workout-adjustment-button" label="Đề xuất điều chỉnh bài tập (AI)" onPress={() => setIsAdjustmentModalVisible(true)} variant="secondary" icon="knowledge" /> : null}
    <AppButton testID="open-plan-history-button" label="Xem lịch sử phiên bản" onPress={() => router.push(`/workout-plans/${plan.id}/history` as never)} variant="secondary" icon="history" />
    {mutableContext && studentOwned && plan.status === 'DRAFT' ? <View style={styles.row}><AppButton testID="edit-plan-button" label="Chỉnh sửa bản nháp" onPress={() => router.push(`/workout-plans/${plan.id}/edit?mode=draft` as never)} icon="edit" variant="secondary" /><AppButton testID="activate-plan-button" label="Kích hoạt" onPress={() => openCommand('ACTIVATE')} icon="check" /></View> : null}
    {mutableContext && studentOwned && ['ACTIVE', 'PAUSED'].includes(plan.status) && currentVersion ? <AppButton testID="publish-plan-version-button" label="Tạo phiên bản chiến lược mới" onPress={() => router.push(`/workout-plans/${plan.id}/edit?mode=publish&versionId=${currentVersion.id}` as never)} icon="history" variant="secondary" /> : null}
    {mutableContext ? <View style={styles.row}>{actions(plan.status, studentOwned).map((target) => <AppButton key={target} label={labels[target] ?? workoutStatusLabels[target]} onPress={() => openCommand(target)} variant="secondary" tone={target === 'ARCHIVED' ? 'danger' : 'brand'} />)}</View> : null}
    {plan.decisionOwnerType === 'TRAINER' && currentVersion?.lockedAt ? <AppButton testID="create-successor-button" label="Tiếp tục bằng kế hoạch tự tập mới" onPress={openSuccessor} accessibilityHint="Tạo bản sao mới; kế hoạch Huấn luyện viên bàn giao vẫn bất biến" variant="secondary" /> : null}
    {commandMutation.isError ? <AppCard testID="plan-command-error" title="Chưa thể cập nhật kế hoạch" message={workoutPlanErrorMessage(commandMutation.error)} state="error" /> : null}

    <Modal visible={pendingCommand !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeCommand}>
      <SafeAreaView style={{ flex: 1, backgroundColor: theme.canvas }}><KeyboardAvoidingView style={[detailStyles.backdrop, { backgroundColor: theme.canvas }]} behavior={Platform.OS === 'ios' ? 'padding' : undefined}><ScrollView style={detailStyles.modalScroll} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets><AppCard testID="plan-command-confirmation"><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận thao tác</Text><Text style={[styles.body, { color: theme.textSecondary }]}>{pendingCommand ? labels[pendingCommand] : ''} sẽ được hệ thống kiểm tra lại với trạng thái và phiên bản mới nhất.</Text><View style={styles.row}><AppButton label="Quay lại" disabled={commandMutation.isPending} onPress={closeCommand} variant="secondary" icon="back" /><AppButton testID="confirm-plan-command" label={commandMutation.isPending ? 'Đang xử lý' : 'Xác nhận'} loading={commandMutation.isPending} disabled={commandMutation.isPending} onPress={() => pendingCommand && commandMutation.mutate(pendingCommand)} variant={pendingCommand === 'ARCHIVED' ? 'danger' : 'primary'} icon="check" /></View></AppCard></ScrollView></KeyboardAvoidingView></SafeAreaView>
    </Modal>

    <Modal visible={successorOpen} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeSuccessor}>
      <SafeAreaView style={{ flex: 1, backgroundColor: theme.canvas }}><KeyboardAvoidingView style={[detailStyles.backdrop, { backgroundColor: theme.canvas }]} behavior={Platform.OS === 'ios' ? 'padding' : undefined}><ScrollView style={detailStyles.modalScroll} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets><AppCard testID="successor-confirmation"><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Tạo kế hoạch kế nhiệm</Text><Text style={[styles.body, { color: theme.textSecondary }]}>Một bản nháp mới do bạn sở hữu sẽ được tạo từ phiên bản đã chọn. Kế hoạch Huấn luyện viên bàn giao và lịch sử gốc không thay đổi.</Text><Text style={[styles.label, { color: theme.textPrimary }]}>Tên kế hoạch mới</Text><TextInput testID="successor-name-input" accessibilityLabel="Tên kế hoạch kế nhiệm" value={successorName} onChangeText={(value) => { successorKey.current = null; setSuccessorName(value); }} maxLength={200} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />{successorMutation.isError ? <AppCard title="Chưa thể tạo kế hoạch kế nhiệm" message={workoutPlanErrorMessage(successorMutation.error)} state="error" /> : null}<View style={styles.row}><AppButton label="Hủy" disabled={successorMutation.isPending} onPress={closeSuccessor} variant="secondary" /><AppButton testID="confirm-successor-button" label={successorMutation.isPending ? 'Đang tạo' : 'Tạo kế hoạch mới'} loading={successorMutation.isPending} disabled={!successorName.trim() || successorMutation.isPending} onPress={() => successorMutation.mutate()} icon="check" /></View></AppCard></ScrollView></KeyboardAvoidingView></SafeAreaView>
    </Modal>
    <AiWorkoutAdjustmentModal
      visible={isAdjustmentModalVisible}
      studentId={studentId}
      initialScope="TEMPLATE_OR_FUTURE_OCCURRENCES"
      planContext={
        currentVersion
          ? {
              planId: plan.id,
              planVersionNumber: currentVersion.versionNumber,
              planAggregateVersion: plan.aggregateVersion,
              planName: plan.name,
              prescriptions,
            }
          : undefined
      }
      onClose={() => setIsAdjustmentModalVisible(false)}
      onAdjustmentApplied={async () => {
        setIsAdjustmentModalVisible(false);
        await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        await query.refetch();
        await versionQuery.refetch();
      }}
    />
  </ScrollView></SafeAreaView>;
}

const detailStyles = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: 'center', padding: spacing.lg },
  modalScroll: { flexGrow: 0, width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' },
});
