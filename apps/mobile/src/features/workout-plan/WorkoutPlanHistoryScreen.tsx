import React, { useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Modal, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { createCommandKey } from '@/services/coachingApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from './components/WorkoutUi';
import { useWorkoutPlan, useWorkoutPlanVersion, useWorkoutPlanVersions, workoutPlanQueryKeys } from './workoutPlanQueries';
import { workoutExerciseStateLabels, workoutPlanErrorMessage } from './workoutPlanMessages';

export function WorkoutPlanHistoryScreen({ planId }: { planId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const planQuery = useWorkoutPlan(studentId, planId);
  const versionsQuery = useWorkoutPlanVersions(studentId, planId);
  const versions = useMemo(() => versionsQuery.data?.pages.flatMap((page) => page.items) ?? [], [versionsQuery.data]);
  const [selectedVersionId, setSelectedVersionId] = useState('');
  const selectedId = selectedVersionId || versions[0]?.id || '';
  const selected = versions.find((item) => item.id === selectedId);
  const versionQuery = useWorkoutPlanVersion(studentId, planId, selectedId);
  const [successorOpen, setSuccessorOpen] = useState(false);
  const [successorName, setSuccessorName] = useState('');
  const commandKey = useRef<string | null>(null);
  const plan = planQuery.data?.plan;

  const successorMutation = useMutation({
    mutationFn: async () => {
      const key = commandKey.current ?? createCommandKey(); commandKey.current = key;
      return workoutPlanApi.successor(planId, selectedId, successorName, key);
    },
    onSuccess: async (result) => {
      commandKey.current = null; setSuccessorOpen(false);
      await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
      AccessibilityInfo.announceForAccessibility('Đã tạo kế hoạch kế nhiệm do Học viên sở hữu.');
      router.replace(`/workout-plans/${result.planId}` as never);
    },
  });
  const openSuccessor = () => { commandKey.current = createCommandKey(); setSuccessorName(`${plan?.name ?? 'Kế hoạch'} - tự tập`); setSuccessorOpen(true); };
  const closeSuccessor = () => { if (!successorMutation.isPending) { commandKey.current = null; setSuccessorOpen(false); } };

  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem lịch sử." />;
  if (planQuery.isPending) return <WorkoutState busy title="Đang tải lịch sử" message="Vui lòng chờ trong giây lát." />;
  if (planQuery.isError || !plan) return <WorkoutState title="Không thể mở lịch sử" message={workoutPlanErrorMessage(planQuery.error)} onRetry={() => planQuery.refetch()} />;

  return <ScrollView testID="workout-plan-history-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Lịch sử {plan.name}</Text>
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Các phiên bản bất biến</Text>
      {versionsQuery.isPending ? <WorkoutState busy title="Đang tải phiên bản" message="Vui lòng chờ trong giây lát." /> : null}
      {versionsQuery.isError ? <WorkoutState testID="version-list-error" title="Không thể tải phiên bản" message={workoutPlanErrorMessage(versionsQuery.error)} onRetry={() => versionsQuery.refetch()} /> : null}
      {!versionsQuery.isPending && !versionsQuery.isError && versions.length === 0 ? <WorkoutState testID="version-list-empty" title="Chưa có phiên bản đã phát hành" message="Bản nháp hiện tại chưa được kích hoạt." /> : null}
      {versions.map((version) => <WorkoutButton key={version.id} label={`Phiên bản ${version.versionNumber}${version.current ? ' · hiện tại' : ' · lịch sử'}`} secondary disabled={version.id === selectedId} onPress={() => setSelectedVersionId(version.id)} />)}
      {versionsQuery.hasNextPage ? <WorkoutButton label="Tải thêm phiên bản" secondary busy={versionsQuery.isFetchingNextPage} disabled={versionsQuery.isFetchingNextPage} onPress={() => versionsQuery.fetchNextPage()} /> : null}
    </WorkoutCard>

    {selectedId ? <WorkoutCard testID="history-version-detail">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Phiên bản {selected?.versionNumber}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{selected?.current ? 'Phiên bản đang dùng' : 'Phiên bản lịch sử chỉ để xem'} · hiệu lực {selected?.effectiveFrom ? new Date(selected.effectiveFrom).toLocaleString('vi-VN') : '—'}</Text>
      {selected?.changeReason ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do: {selected.changeReason}</Text> : null}
      {selected?.changeSummary ? <Text style={[styles.body, { color: theme.textSecondary }]}>Tóm tắt: {selected.changeSummary}</Text> : null}
      {versionQuery.isPending ? <WorkoutState busy title="Đang tải nội dung phiên bản" message="Vui lòng chờ trong giây lát." /> : null}
      {versionQuery.isError ? <WorkoutState title="Không thể tải nội dung phiên bản" message={workoutPlanErrorMessage(versionQuery.error)} onRetry={() => versionQuery.refetch()} /> : null}
      {versionQuery.data?.sessions.map((session) => <View key={session.id}><Text style={[styles.label, { color: theme.textPrimary }]}>Tuần {session.weekNumber}, ngày {session.dayNumber}: {session.name}</Text>{session.prescriptions.map((item) => <View key={item.id}><Text style={[styles.body, { color: theme.textPrimary }]}>{item.sequenceNumber}. {item.exercise.exerciseName ?? 'Bài tập không khả dụng'}{item.exercise.variationName ? ` · ${item.exercise.variationName}` : ''}</Text><Text style={[styles.body, { color: item.exercise.state === 'ACTIVE' ? theme.textSecondary : theme.warningText }]}>{workoutExerciseStateLabels[item.exercise.state]}</Text>{item.exercise.canonicalExerciseName ? <Text style={[styles.body, { color: theme.textSecondary }]}>Canonical hiện tại: {item.exercise.canonicalExerciseName} — không thay thế bản ghi gốc.</Text> : null}</View>)}</View>)}
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản đã phát hành không có thao tác chỉnh sửa trực tiếp.</Text>
      {plan.decisionOwnerType === 'TRAINER' && selected?.lockedAt ? <WorkoutButton testID="history-successor-button" label="Tạo kế hoạch tự tập từ phiên bản này" onPress={openSuccessor} /> : null}
    </WorkoutCard> : null}
    <WorkoutButton label="Quay lại kế hoạch" secondary onPress={() => router.back()} />

    <Modal visible={successorOpen} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeSuccessor}>
      <View style={{ flex: 1, justifyContent: 'center', padding: spacing.lg, backgroundColor: '#00000066' }}><WorkoutCard testID="history-successor-confirmation"><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận kế hoạch kế nhiệm</Text><Text style={[styles.body, { color: theme.textSecondary }]}>Nguồn vẫn là bản ghi Trainer-authored bất biến. Hệ thống sẽ tạo một Student-owned DRAFT mới với lineage rõ ràng.</Text><TextInput accessibilityLabel="Tên kế hoạch kế nhiệm" value={successorName} onChangeText={(value) => { commandKey.current = null; setSuccessorName(value); }} maxLength={200} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />{successorMutation.isError ? <WorkoutState title="Chưa thể tạo kế hoạch" message={workoutPlanErrorMessage(successorMutation.error)} /> : null}<View style={styles.row}><WorkoutButton label="Hủy" secondary disabled={successorMutation.isPending} onPress={closeSuccessor} /><WorkoutButton testID="confirm-history-successor" label={successorMutation.isPending ? 'Đang tạo' : 'Tạo kế hoạch mới'} busy={successorMutation.isPending} disabled={!successorName.trim() || successorMutation.isPending} onPress={() => successorMutation.mutate()} /></View></WorkoutCard></View>
    </Modal>
  </ScrollView>;
}
