import { SafeAreaView } from 'react-native-safe-area-context';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { ApiError } from '@/types/auth';
import type { CancelPlannedWorkoutRequest, ReschedulePlannedWorkoutRequest, WorkoutScheduleChangeDestination } from '@/types/workoutSchedule';
import { workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { AiWorkoutAdjustmentModal, ExercisePrescriptionOption } from '@/features/workout-plan/components/AiWorkoutAdjustmentModal';
import { AiWorkoutRescheduleModal } from '@/features/ai-workout-reschedule/AiWorkoutRescheduleModal';
import { rescheduleQueryKeys } from '@/features/ai-workout-reschedule/rescheduleQueries';
import { useWorkoutPlan, useWorkoutPlanVersion, workoutPlanQueryKeys } from '@/features/workout-plan/workoutPlanQueries';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { LocalDateTimeInputField } from '@/components/ui/LocalDateTimeInputField';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { formatTimezoneDisplay, resolveExistingRecordTimezone } from '@/features/shared/timezoneUtils';
import { workoutScheduleQueryKeys, useWorkoutScheduleDetail } from './workoutScheduleQueries';
import { formatScheduleTime, plannedWorkoutStatusLabels } from './scheduleDisplay';
import { scheduleChangeErrorMessage, validateScheduleDestination } from './scheduleChange';

type Command =
  | { kind: 'RESCHEDULE'; occurrenceId: string; body: ReschedulePlannedWorkoutRequest }
  | { kind: 'CANCEL'; occurrenceId: string; body: CancelPlannedWorkoutRequest };

const EMPTY_DESTINATION: WorkoutScheduleChangeDestination = {
  timezone: '', localStart: '', localEnd: '', startUtcOffset: '', endUtcOffset: '',
};

export function StudentWorkoutScheduleDetailScreen({
  occurrenceId,
  initialRecommendationId,
}: {
  occurrenceId: string;
  initialRecommendationId?: string;
}) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const [permissionLost, setPermissionLost] = useState(false);
  const query = useWorkoutScheduleDetail(studentId, occurrenceId, allowed && !permissionLost);
  const item = query.data;
  const [mode, setMode] = useState<'VIEW' | 'RESCHEDULE' | 'CANCEL'>('VIEW');
  const [destination, setDestination] = useState<WorkoutScheduleChangeDestination>(EMPTY_DESTINATION);
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState(false);
  const [error, setError] = useState('');
  const [retryCommand, setRetryCommand] = useState<Command | null>(null);
  const [needsReview, setNeedsReview] = useState(false);
  const submitting = useRef(false);
  const [isAdjustmentModalVisible, setIsAdjustmentModalVisible] = useState(Boolean(initialRecommendationId));
  const [activeRecommendationId, setActiveRecommendationId] = useState<string | undefined>(initialRecommendationId);
  const [isRescheduleModalVisible, setIsRescheduleModalVisible] = useState(false);
  const [lastAppliedSuccessorId, setLastAppliedSuccessorId] = useState<string | null>(null);

  const handleOpenRescheduleModal = useCallback(() => {
    setLastAppliedSuccessorId(null);
    setIsRescheduleModalVisible(true);
  }, []);

  const planId = item?.planSummary?.planId ?? '';
  const planVersionId = item?.planSummary?.planVersionId ?? '';
  const planQuery = useWorkoutPlan(
    studentId,
    planId,
    Boolean(allowed && !permissionLost && planId)
  );
  const versionQuery = useWorkoutPlanVersion(
    studentId,
    planId,
    planVersionId,
    Boolean(allowed && !permissionLost && planId && planVersionId)
  );

  const currentSession = useMemo(() => {
    if (!versionQuery.data?.sessions || !item?.sessionSummary) return null;
    return (
      versionQuery.data.sessions.find((s) => s.id === item.sessionSummary?.planSessionId) ??
      versionQuery.data.sessions.find(
        (s) =>
          s.weekNumber === item.sessionSummary?.weekNumber &&
          s.dayNumber === item.sessionSummary?.dayNumber
      ) ??
      null
    );
  }, [versionQuery.data, item]);

  const prescriptions: ExercisePrescriptionOption[] = useMemo(() => {
    if (!currentSession?.prescriptions) return [];
    return currentSession.prescriptions.map((p) => ({
      id: p.id,
      exerciseName: p.exercise.exerciseName || 'Bài tập',
      dayNumber: currentSession.dayNumber,
      targetLoad: p.targetLoad,
      loadUnit: null,
      targetSets: p.targetSets,
      targetRepsMin: p.targetRepsMin,
      targetRepsMax: p.targetRepsMax,
      durationSeconds: p.durationSeconds,
    }));
  }, [currentSession]);

  useEffect(() => {
    if (!permissionLost && query.error instanceof ApiError && [403, 404].includes(query.error.status)) {
      client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
      client.removeQueries({ queryKey: rescheduleQueryKeys.scope(studentId, 'STUDENT', studentId) });
      queueMicrotask(() => setPermissionLost(true));
    }
  }, [client, permissionLost, query.error, studentId]);

  useEffect(() => {
    if (!allowed && studentId) client.removeQueries({ queryKey: rescheduleQueryKeys.scope(studentId, 'STUDENT', studentId) });
  }, [allowed, client, studentId]);

  const resetCommand = () => { setRetryCommand(null); setPreview(false); setError(''); };
  const editDestination = (patch: Partial<WorkoutScheduleChangeDestination>) => {
    setDestination((current) => ({ ...current, ...patch }));
    resetCommand();
  };
  const clearSensitive = () => {
    setPermissionLost(true);
    setRetryCommand(null);
    client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
    client.removeQueries({ queryKey: rescheduleQueryKeys.scope(studentId, 'STUDENT', studentId) });
  };
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
    await query.refetch();
  };
  const mutation = useMutation({
    mutationFn: (command: Command) => command.kind === 'RESCHEDULE'
      ? workoutScheduleApi.reschedule(command.occurrenceId, command.body)
      : workoutScheduleApi.cancel(command.occurrenceId, command.body),
    onSuccess: async (result) => {
      setRetryCommand(null); setMode('VIEW'); setPreview(false); setError(''); setNeedsReview(false);
      await client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
      if (result.changeType === 'RESCHEDULE') {
        if (result.replacementOccurrenceId) {
          AccessibilityInfo.announceForAccessibility('Đã đổi lịch. Đang mở buổi tập mới.');
          router.replace(`/workouts/schedule/${result.replacementOccurrenceId}` as never);
        } else {
          setError('Máy chủ đã xác nhận đổi lịch nhưng chưa trả buổi thay thế. Hãy tải lại lịch để xem kết quả.');
          await query.refetch();
        }
      } else {
        AccessibilityInfo.announceForAccessibility('Đã hủy buổi tập theo lịch.');
        await query.refetch();
      }
    },
    onError: async (cause) => {
      const status = cause instanceof ApiError ? cause.status : undefined;
      setError(scheduleChangeErrorMessage(cause));
      if (status === 403 || status === 404) clearSensitive();
      else if (status === 409) {
        setRetryCommand(null); setPreview(false); setMode('VIEW'); setNeedsReview(true);
        await refresh();
      } else if (status !== 0 && (status === undefined || status < 500)) setRetryCommand(null);
    },
    onSettled: () => { submitting.current = false; },
  });

  const send = (command: Command) => {
    if (submitting.current || mutation.isPending || permissionLost || needsReview) return;
    submitting.current = true; setRetryCommand(command); setError(''); mutation.mutate(command);
  };
  const submit = () => {
    if (!item || !preview || query.isFetching || submitting.current || mutation.isPending) return;
    if (mode === 'RESCHEDULE') {
      if (!item.availableActions.includes('RESCHEDULE')) return;
      const issue = validateScheduleDestination(destination);
      if (issue) { setError(issue); setPreview(false); return; }
      send(retryCommand?.kind === 'RESCHEDULE' ? retryCommand : {
        kind: 'RESCHEDULE', occurrenceId, body: { expectedOccurrenceVersion: item.occurrenceVersion, destination, commandKey: createCommandKey() },
      });
    } else if (mode === 'CANCEL') {
      if (!item.availableActions.includes('CANCEL')) return;
      if (new Date(item.plannedStartAt).getTime() <= Date.now() && !reason.trim()) {
        setError('Buổi tập đã qua giờ bắt đầu; hãy nhập lý do hủy.'); return;
      }
      send(retryCommand?.kind === 'CANCEL' ? retryCommand : {
        kind: 'CANCEL', occurrenceId, body: { expectedOccurrenceVersion: item.occurrenceVersion, reason: reason.trim() || null, commandKey: createCommandKey() },
      });
    }
  };

  if (!allowed) return <AppCard title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem lịch tập." state="info" />;
  return <SafeAreaView style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="student-schedule-detail" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]} automaticallyAdjustKeyboardInsets keyboardShouldPersistTaps="handled">
    <View style={{ flexDirection: 'row', gap: spacing.md, alignItems: 'center' }}><AppIcon name="calendar" color={theme.primary} /><Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary, flex: 1 }]}>Chi tiết buổi tập theo lịch</Text></View>
    <AppButton label="Quay lại lịch tập" onPress={() => router.back()} variant="secondary" icon="back" />
    {query.isPending ? <AppCard title="Đang tải buổi tập" message="Vui lòng chờ." state="loading" /> : null}
    {query.isError && !permissionLost ? <AppCard testID="schedule-detail-error" title="Không thể tải buổi tập" message={scheduleChangeErrorMessage(query.error)} onRetry={() => query.refetch()} state="error" /> : null}
    {permissionLost ? <AppCard testID="schedule-detail-concealed" title="Không còn quyền xem" message="Buổi tập không còn khả dụng. Hãy quay lại lịch và tải dữ liệu mới." state="info" /> : null}
    {item && !query.isError && !permissionLost ? <>
      <AppCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{item.sessionSummary?.name?.trim() || 'Buổi tập theo lịch'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{plannedWorkoutStatusLabels[item.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {item.planSummary?.planName?.trim() || 'Chưa có tên kế hoạch trong bản ghi cũ'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {item.sessionSummary?.focus?.trim() || 'Chưa có thông tin'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu: {formatScheduleTime(item.plannedStartAt, item.timezone, user?.timezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc: {item.plannedEndAt ? formatScheduleTime(item.plannedEndAt, item.timezone, user?.timezone) : 'Chưa rõ trong bản ghi cũ'}</Text>
        {item.localStart && item.localEnd && item.timezone && item.startUtcOffset && item.endUtcOffset
          ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ địa phương: {item.localStart} UTC{item.startUtcOffset} → {item.localEnd} UTC{item.endUtcOffset} · {formatTimezoneDisplay(item.timezone)}</Text>
          : <Text style={[styles.body, { color: theme.textSecondary }]}>Bản ghi cũ chưa có đủ giờ địa phương, múi giờ hoặc UTC offset.</Text>}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Mốc đầu chuỗi: {formatScheduleTime(item.originalPlannedStartAt, item.timezone, user?.timezone)}</Text>
        {item.rescheduledFromOccurrenceId ? <AppButton testID="schedule-predecessor" label="Xem buổi tập trước khi đổi lịch" onPress={() => router.push(`/workouts/schedule/${item.rescheduledFromOccurrenceId}` as never)} variant="secondary" icon="calendar" /> : null}
        {item.replacementOccurrenceId ? <AppButton testID="schedule-replacement" label="Xem buổi tập thay thế" onPress={() => router.push(`/workouts/schedule/${item.replacementOccurrenceId}` as never)} variant="secondary" /> : null}
        {item.linkedAppointmentId ? <>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi tập đang liên kết với một lịch hẹn riêng. Đổi lịch chỉ hợp lệ khi buổi mới vẫn nằm trọn trong chính lịch hẹn đó.</Text>
          <AppButton label="Xem lịch hẹn đang liên kết" onPress={() => router.push(`/appointments/${item.linkedAppointmentId}` as never)} variant="secondary" icon="calendar" />
        </> : null}
      </AppCard>
      {needsReview ? <AppCard testID="schedule-change-review" title="Lịch đã thay đổi" message="Dữ liệu mới đã được tải. Hãy xem lại rồi chọn thao tác và xác nhận lại; lệnh cũ không được gửi lại." onRetry={() => { setNeedsReview(false); setError(''); }} state="error" /> : null}
      {mode === 'VIEW' && !needsReview ? <View style={styles.row}>
        {item.status === 'SCHEDULED' ? <AppButton testID="ai-workout-adjustment-button" label="Đề xuất điều chỉnh bài tập (AI)" onPress={() => setIsAdjustmentModalVisible(true)} variant="secondary" icon="knowledge" /> : null}
        {item.status === 'SCHEDULED' ? <AppButton testID="ai-workout-reschedule-button" label="Đề xuất dời lịch tập (AI)" onPress={handleOpenRescheduleModal} variant="secondary" icon="calendar" /> : null}
        {item.availableActions.includes('RESCHEDULE') ? <AppButton testID="schedule-reschedule" label="Đổi lịch buổi này" onPress={() => { setMode('RESCHEDULE'); setDestination({ ...EMPTY_DESTINATION, timezone: resolveExistingRecordTimezone(item.timezone) }); resetCommand(); }} icon="calendar" /> : null}
        {item.availableActions.includes('CANCEL') ? <AppButton testID="schedule-cancel" label="Hủy buổi này" onPress={() => { setMode('CANCEL'); setReason(''); resetCommand(); }} variant="danger" /> : null}
      </View> : null}
      {mode === 'RESCHEDULE' && item.availableActions.includes('RESCHEDULE') && !needsReview ? <>
        <AppCard testID="schedule-reschedule-form">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Chọn giờ mới</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn ngày giờ địa phương và xác nhận UTC offset phù hợp. Không suy giờ kết thúc từ kế hoạch.</Text>
          <Text testID="schedule-timezone-display" style={[styles.label, { color: theme.textPrimary }]}>Khung giờ áp dụng: {formatTimezoneDisplay(destination.timezone)}</Text>
          {(['Start', 'End'] as const).map((part) => {
            const local = part === 'Start' ? destination.localStart : destination.localEnd;
            const offset = part === 'Start' ? destination.startUtcOffset : destination.endUtcOffset;
            const choices = validProposalOffsets(destination.timezone, local);
            return <View key={part} style={{ gap: spacing.xs }}>
              <LocalDateTimeInputField
                label={part === 'Start' ? 'Bắt đầu mới' : 'Kết thúc mới'}
                value={local}
                onChange={(value) => editDestination(part === 'Start' ? { localStart: value, startUtcOffset: '' } : { localEnd: value, endUtcOffset: '' })}
                dateTestID={`schedule-local-${part.toLowerCase()}-date`}
                timeTestID={`schedule-local-${part.toLowerCase()}-time`}
                dateAccessibilityLabel={`Ngày ${part === 'Start' ? 'bắt đầu' : 'kết thúc'} địa phương`}
                timeAccessibilityLabel={`Giờ ${part === 'Start' ? 'bắt đầu' : 'kết thúc'} địa phương (HH:mm)`}
                sessionKey={`${occurrenceId}:${mode}`}
              />
              {local && !choices.length ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Giờ không tồn tại trong múi giờ này hoặc sai định dạng.</Text> : null}
              {choices.length > 1 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ lặp khi đổi DST. Hãy chọn một offset tường minh.</Text> : null}
              <View style={styles.row}>{choices.map((choice) => <AppButton key={choice.offset} label={`UTC${choice.offset}`}
                onPress={() => editDestination(part === 'Start' ? { startUtcOffset: choice.offset } : { endUtcOffset: choice.offset })} variant={offset !== choice.offset ? 'secondary' : 'primary'} />)}</View>
            </View>;
          })}
          <AppButton testID="schedule-review-reschedule" label="Xem trước lịch mới" disabled={mutation.isPending} onPress={() => {
            const issue = validateScheduleDestination(destination); setError(issue ?? ''); setPreview(!issue);
          }} icon="calendar" />
        </AppCard>
        {preview ? <AppCard testID="schedule-reschedule-preview">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận đổi một buổi tập</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi cũ được giữ trong lịch sử; buổi mới sẽ có ID riêng, giữ nguyên nguồn kế hoạch và nội dung tập.</Text>
          {item.linkedAppointmentId ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lịch hẹn không tự dời. Buổi mới phải nằm trọn trong lịch hẹn đã xác nhận hiện tại; liên kết đang hoạt động sẽ chuyển sang buổi mới. Nếu cần giờ ngoài lịch hẹn, hãy hoàn tất yêu cầu đổi lịch hẹn trước rồi xem và xác nhận lại buổi tập.</Text> : null}
          <Text style={[styles.body, { color: theme.textPrimary }]}>{destination.localStart} UTC{destination.startUtcOffset} → {destination.localEnd} UTC{destination.endUtcOffset} · {formatTimezoneDisplay(destination.timezone)}</Text>
          <AppButton testID="schedule-confirm-reschedule" label="Xác nhận đổi lịch" loading={mutation.isPending} disabled={mutation.isPending} onPress={submit} icon="check" />
        </AppCard> : null}
      </> : null}
      {mode === 'CANCEL' && item.availableActions.includes('CANCEL') && !needsReview ? <AppCard testID="schedule-cancel-form">
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận hủy buổi tập</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi này sẽ chuyển sang Đã hủy và vẫn xem được trong lịch sử. Không tạo buổi thay thế.</Text>
        {item.linkedAppointmentId ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Hủy buổi tập không hủy lịch hẹn. Lịch hẹn vẫn được xác nhận; Huấn luyện viên sẽ được thông báo. Nếu muốn hủy lịch hẹn, hãy thực hiện riêng trong phần Lịch hẹn.</Text> : null}
        <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do hủy buổi tập</Text>
        <TextInput testID="schedule-cancel-reason" accessibilityLabel="Lý do hủy buổi tập" placeholder="Lý do hủy (bắt buộc nếu đã qua giờ bắt đầu)" multiline maxLength={1000} value={reason}
          onChangeText={(value) => { setReason(value); resetCommand(); }} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
        <AppButton testID="schedule-review-cancel" label="Xem lại trước khi hủy" onPress={() => {
          const missing = new Date(item.plannedStartAt).getTime() <= Date.now() && !reason.trim();
          setError(missing ? 'Buổi tập đã qua giờ bắt đầu; hãy nhập lý do hủy.' : ''); setPreview(!missing);
        }} />
        {preview ? <AppButton testID="schedule-confirm-cancel" label="Xác nhận hủy buổi này" loading={mutation.isPending} disabled={mutation.isPending} onPress={submit} variant="danger" icon="check" /> : null}
      </AppCard> : null}
      {mode !== 'VIEW' ? <AppButton label="Không thay đổi" disabled={mutation.isPending} onPress={() => { setMode('VIEW'); resetCommand(); }} variant="secondary" icon="back" /> : null}
      {error ? <AppCard testID="schedule-change-error" title="Chưa thể cập nhật lịch" message={error} onRetry={retryCommand && !needsReview && !permissionLost ? () => send(retryCommand) : undefined} state="error" /> : null}
      <AiWorkoutAdjustmentModal
        visible={isAdjustmentModalVisible}
        studentId={studentId}
        initialScope="ONE_MATERIALIZED_OCCURRENCE"
        initialRecommendationId={activeRecommendationId}
        occurrenceContext={{
          plannedWorkoutId: occurrenceId,
          occurrenceVersion: item.occurrenceVersion,
          sessionName: item.sessionSummary?.name || undefined,
          prescriptions,
        }}
        planContext={
          item.planSummary
            ? {
                planId: item.planSummary.planId,
                planVersionNumber: item.planSummary.planVersionNumber,
                planAggregateVersion: planQuery.data?.plan?.aggregateVersion,
                prescriptions,
              }
            : undefined
        }
        onClose={() => {
          setIsAdjustmentModalVisible(false);
          setActiveRecommendationId(undefined);
        }}
        onAdjustmentApplied={async () => {
          setIsAdjustmentModalVisible(false);
          setActiveRecommendationId(undefined);
          await client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
          if (planId) {
            await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.detail(studentId, planId) });
          }
          await query.refetch();
        }}
      />
      {isRescheduleModalVisible ? <AiWorkoutRescheduleModal
        visible={true}
        actorId={studentId}
        capability="STUDENT"
        studentId={studentId}
        occurrence={item}
        authorized={allowed && !permissionLost}
        onClose={async () => {
          setIsRescheduleModalVisible(false);
          await client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
          if (lastAppliedSuccessorId) {
            const nextId = lastAppliedSuccessorId;
            setLastAppliedSuccessorId(null);
            AccessibilityInfo.announceForAccessibility('Đã đổi lịch. Đang mở buổi tập mới.');
            router.replace(`/workouts/schedule/${nextId}` as never);
          } else {
            await query.refetch();
          }
        }}
        onApplied={(result) => setLastAppliedSuccessorId(result.resultingEntityId || null)}
      /> : null}
    </> : null}
  </ScrollView></SafeAreaView>;
}
