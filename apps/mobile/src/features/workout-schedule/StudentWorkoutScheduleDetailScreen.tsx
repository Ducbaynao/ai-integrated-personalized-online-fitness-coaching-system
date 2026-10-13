import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { ApiError } from '@/types/auth';
import type { CancelPlannedWorkoutRequest, ReschedulePlannedWorkoutRequest, WorkoutScheduleChangeDestination } from '@/types/workoutSchedule';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { proposalTimezones, validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { workoutScheduleQueryKeys, useWorkoutScheduleDetail } from './workoutScheduleQueries';
import { formatScheduleTime, plannedWorkoutStatusLabels } from './scheduleDisplay';
import { scheduleChangeErrorMessage, validateScheduleDestination } from './scheduleChange';

type Command =
  | { kind: 'RESCHEDULE'; occurrenceId: string; body: ReschedulePlannedWorkoutRequest }
  | { kind: 'CANCEL'; occurrenceId: string; body: CancelPlannedWorkoutRequest };

const EMPTY_DESTINATION: WorkoutScheduleChangeDestination = {
  timezone: '', localStart: '', localEnd: '', startUtcOffset: '', endUtcOffset: '',
};

export function StudentWorkoutScheduleDetailScreen({ occurrenceId }: { occurrenceId: string }) {
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
  const [timezoneSearch, setTimezoneSearch] = useState('');
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState(false);
  const [error, setError] = useState('');
  const [retryCommand, setRetryCommand] = useState<Command | null>(null);
  const [needsReview, setNeedsReview] = useState(false);
  const submitting = useRef(false);

  useEffect(() => {
    if (!permissionLost && query.error instanceof ApiError && [403, 404].includes(query.error.status)) {
      client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
      queueMicrotask(() => setPermissionLost(true));
    }
  }, [client, permissionLost, query.error, studentId]);

  const resetCommand = () => { setRetryCommand(null); setPreview(false); setError(''); };
  const editDestination = (patch: Partial<WorkoutScheduleChangeDestination>) => {
    setDestination((current) => ({ ...current, ...patch }));
    resetCommand();
  };
  const clearSensitive = () => {
    setPermissionLost(true);
    setRetryCommand(null);
    client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
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

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem lịch tập." />;
  return <ScrollView testID="student-schedule-detail" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết buổi tập theo lịch</Text>
    <WorkoutButton label="Quay lại lịch tập" secondary onPress={() => router.back()} />
    {query.isPending ? <WorkoutState busy title="Đang tải buổi tập" message="Vui lòng chờ." /> : null}
    {query.isError && !permissionLost ? <WorkoutState testID="schedule-detail-error" title="Không thể tải buổi tập" message={scheduleChangeErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {permissionLost ? <WorkoutState testID="schedule-detail-concealed" title="Không còn quyền xem" message="Buổi tập không còn khả dụng. Hãy quay lại lịch và tải dữ liệu mới." /> : null}
    {item && !query.isError && !permissionLost ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{item.sessionSummary?.name?.trim() || 'Buổi tập theo lịch'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{plannedWorkoutStatusLabels[item.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {item.planSummary?.planName?.trim() || 'Chưa có tên kế hoạch trong bản ghi cũ'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {item.sessionSummary?.focus?.trim() || 'Chưa có thông tin'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu: {formatScheduleTime(item.plannedStartAt, item.timezone, user?.timezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc: {item.plannedEndAt ? formatScheduleTime(item.plannedEndAt, item.timezone, user?.timezone) : 'Chưa rõ trong bản ghi cũ'}</Text>
        {item.localStart && item.localEnd && item.timezone && item.startUtcOffset && item.endUtcOffset
          ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ địa phương: {item.localStart} UTC{item.startUtcOffset} → {item.localEnd} UTC{item.endUtcOffset} · {item.timezone}</Text>
          : <Text style={[styles.body, { color: theme.textSecondary }]}>Bản ghi cũ chưa có đủ giờ địa phương, múi giờ hoặc UTC offset.</Text>}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Mốc đầu chuỗi: {formatScheduleTime(item.originalPlannedStartAt, item.timezone, user?.timezone)}</Text>
        {item.rescheduledFromOccurrenceId ? <WorkoutButton testID="schedule-predecessor" label="Xem buổi tập trước khi đổi lịch" secondary onPress={() => router.push(`/workouts/schedule/${item.rescheduledFromOccurrenceId}` as never)} /> : null}
        {item.replacementOccurrenceId ? <WorkoutButton testID="schedule-replacement" label="Xem buổi tập thay thế" secondary onPress={() => router.push(`/workouts/schedule/${item.replacementOccurrenceId}` as never)} /> : null}
      </WorkoutCard>
      {needsReview ? <WorkoutState testID="schedule-change-review" title="Lịch đã thay đổi" message="Dữ liệu mới đã được tải. Hãy xem lại rồi chọn thao tác và xác nhận lại; lệnh cũ không được gửi lại." onRetry={() => { setNeedsReview(false); setError(''); }} /> : null}
      {mode === 'VIEW' && !needsReview ? <View style={styles.row}>
        {item.availableActions.includes('RESCHEDULE') ? <WorkoutButton testID="schedule-reschedule" label="Đổi lịch buổi này" onPress={() => { setMode('RESCHEDULE'); setDestination(EMPTY_DESTINATION); resetCommand(); }} /> : null}
        {item.availableActions.includes('CANCEL') ? <WorkoutButton testID="schedule-cancel" label="Hủy buổi này" danger onPress={() => { setMode('CANCEL'); setReason(''); resetCommand(); }} /> : null}
      </View> : null}
      {mode === 'RESCHEDULE' && item.availableActions.includes('RESCHEDULE') && !needsReview ? <>
        <WorkoutCard testID="schedule-reschedule-form">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Chọn giờ mới</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn rõ múi giờ IANA, ngày giờ địa phương và UTC offset. Không suy giờ kết thúc từ kế hoạch.</Text>
          {item.timezone ? <WorkoutButton label={`Dùng múi giờ hiện tại: ${item.timezone}`} secondary onPress={() => editDestination({ timezone: item.timezone ?? '', startUtcOffset: '', endUtcOffset: '' })} /> : null}
          <TextInput testID="schedule-timezone-search" accessibilityLabel="Tìm múi giờ IANA" placeholder="Tìm, ví dụ Asia/Ho_Chi_Minh" value={timezoneSearch} onChangeText={setTimezoneSearch} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
          <Text style={[styles.label, { color: theme.textPrimary }]}>Múi giờ đã chọn: {destination.timezone || 'Chưa chọn'}</Text>
          {proposalTimezones(timezoneSearch).map((zone) => <WorkoutButton key={zone} label={zone} secondary={zone !== destination.timezone} onPress={() => editDestination({ timezone: zone, startUtcOffset: '', endUtcOffset: '' })} />)}
          {(['Start', 'End'] as const).map((part) => {
            const local = part === 'Start' ? destination.localStart : destination.localEnd;
            const offset = part === 'Start' ? destination.startUtcOffset : destination.endUtcOffset;
            const choices = validProposalOffsets(destination.timezone, local);
            return <View key={part} style={{ gap: spacing.xs }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>{part === 'Start' ? 'Bắt đầu mới' : 'Kết thúc mới'}</Text>
              <TextInput testID={`schedule-local-${part.toLowerCase()}`} accessibilityLabel={`${part === 'Start' ? 'Bắt đầu' : 'Kết thúc'} địa phương`} placeholder="YYYY-MM-DDTHH:mm" value={local}
                onChangeText={(value) => editDestination(part === 'Start' ? { localStart: value, startUtcOffset: '' } : { localEnd: value, endUtcOffset: '' })}
                style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
              {local && !choices.length ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Giờ không tồn tại trong múi giờ này hoặc sai định dạng.</Text> : null}
              {choices.length > 1 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ lặp khi đổi DST. Hãy chọn một offset tường minh.</Text> : null}
              <View style={styles.row}>{choices.map((choice) => <WorkoutButton key={choice.offset} label={`UTC${choice.offset}`} secondary={offset !== choice.offset}
                onPress={() => editDestination(part === 'Start' ? { startUtcOffset: choice.offset } : { endUtcOffset: choice.offset })} />)}</View>
            </View>;
          })}
          <WorkoutButton testID="schedule-review-reschedule" label="Xem trước lịch mới" disabled={mutation.isPending} onPress={() => {
            const issue = validateScheduleDestination(destination); setError(issue ?? ''); setPreview(!issue);
          }} />
        </WorkoutCard>
        {preview ? <WorkoutCard testID="schedule-reschedule-preview">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận đổi một buổi tập</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi cũ được giữ trong lịch sử; buổi mới sẽ có ID riêng, giữ nguyên nguồn kế hoạch và nội dung tập.</Text>
          <Text style={[styles.body, { color: theme.textPrimary }]}>{destination.localStart} UTC{destination.startUtcOffset} → {destination.localEnd} UTC{destination.endUtcOffset} · {destination.timezone}</Text>
          <WorkoutButton testID="schedule-confirm-reschedule" label="Xác nhận đổi lịch" busy={mutation.isPending} disabled={mutation.isPending} onPress={submit} />
        </WorkoutCard> : null}
      </> : null}
      {mode === 'CANCEL' && item.availableActions.includes('CANCEL') && !needsReview ? <WorkoutCard testID="schedule-cancel-form">
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận hủy buổi tập</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi này sẽ chuyển sang Đã hủy và vẫn xem được trong lịch sử. Không tạo buổi thay thế.</Text>
        <TextInput testID="schedule-cancel-reason" accessibilityLabel="Lý do hủy buổi tập" placeholder="Lý do hủy (bắt buộc nếu đã qua giờ bắt đầu)" multiline maxLength={1000} value={reason}
          onChangeText={(value) => { setReason(value); resetCommand(); }} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
        <WorkoutButton testID="schedule-review-cancel" label="Xem lại trước khi hủy" onPress={() => {
          const missing = new Date(item.plannedStartAt).getTime() <= Date.now() && !reason.trim();
          setError(missing ? 'Buổi tập đã qua giờ bắt đầu; hãy nhập lý do hủy.' : ''); setPreview(!missing);
        }} />
        {preview ? <WorkoutButton testID="schedule-confirm-cancel" label="Xác nhận hủy buổi này" danger busy={mutation.isPending} disabled={mutation.isPending} onPress={submit} /> : null}
      </WorkoutCard> : null}
      {mode !== 'VIEW' ? <WorkoutButton label="Không thay đổi" secondary disabled={mutation.isPending} onPress={() => { setMode('VIEW'); resetCommand(); }} /> : null}
      {error ? <WorkoutState testID="schedule-change-error" title="Chưa thể cập nhật lịch" message={error} onRetry={retryCommand && !needsReview && !permissionLost ? () => send(retryCommand) : undefined} /> : null}
    </> : null}
  </ScrollView>;
}
