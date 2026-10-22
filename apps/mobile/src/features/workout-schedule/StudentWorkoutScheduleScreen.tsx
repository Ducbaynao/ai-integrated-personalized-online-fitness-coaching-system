import React, { useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutExecutionApi } from '@/services/workoutExecutionApi';
import { workoutExecutionQueryKeys } from '@/features/workout-execution/workoutExecutionQueries';
import { workoutExecutionErrorMessage } from '@/features/workout-execution/workoutExecutionMessages';
import { useWorkoutSchedule, workoutScheduleQueryKeys } from './workoutScheduleQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { ApiError } from '@/types/auth';
import type { PlannedWorkoutOccurrence, SkipPlannedWorkoutResponse } from '@/types/workoutSchedule';
import type { WorkoutExecutionDetail } from '@/types/workoutExecution';
import { calendarWindow, formatScheduleTime, plannedWorkoutStatusLabels } from './scheduleDisplay';
export { calendarWindow, formatScheduleTime } from './scheduleDisplay';

const DAY = 24 * 60 * 60 * 1000;
type Command = { action: 'START' | 'SKIP'; occurrenceId: string; expectedOccurrenceVersion: number; commandKey: string };

function scheduleErrorMessage(error: unknown) {
  if (error instanceof ApiError && error.status === 403) return 'Bạn không có quyền xem lịch tập này.';
  if (error instanceof ApiError && error.status === 400) return 'Khoảng thời gian xem lịch không hợp lệ.';
  return workoutExecutionErrorMessage(error);
}

export function StudentWorkoutScheduleScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const [anchor] = useState(() => Date.now() - 7 * DAY);
  const [offset, setOffset] = useState(0);
  const range = useMemo(() => calendarWindow(anchor, offset), [anchor, offset]);
  const query = useWorkoutSchedule(studentId, range.from, range.until, allowed);
  const items = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);
  const [confirmSkip, setConfirmSkip] = useState<PlannedWorkoutOccurrence | null>(null);
  const [failedCommand, setFailedCommand] = useState<Command | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const submitting = useRef(false);
  const commandKeys = useRef<Record<string, string>>({});

  const refreshAuthority = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) }),
      client.invalidateQueries({ queryKey: workoutExecutionQueryKeys.current(studentId) }),
    ]);
  };

  const mutation = useMutation({
    mutationFn: async (command: Command): Promise<WorkoutExecutionDetail | SkipPlannedWorkoutResponse> => command.action === 'START'
      ? workoutExecutionApi.start(command.occurrenceId, command.expectedOccurrenceVersion, command.commandKey)
      : workoutExecutionApi.skip(command.occurrenceId, command.expectedOccurrenceVersion, command.commandKey),
    onSuccess: async (result, command) => {
      delete commandKeys.current[`${command.action}:${command.occurrenceId}`];
      setConfirmSkip(null);
      setFailedCommand(null);
      setActionError(null);
      if (command.action === 'START' && 'execution' in result) {
        client.setQueryData(workoutExecutionQueryKeys.current(studentId), result);
        client.setQueryData(workoutExecutionQueryKeys.detail(studentId, result.execution.executionId), result);
      }
      await refreshAuthority();
      await client.invalidateQueries({ queryKey: workoutExecutionQueryKeys.history(studentId) });
      if (command.action === 'START') {
        AccessibilityInfo.announceForAccessibility('Đã bắt đầu buổi tập.');
        router.push('/workouts/current' as never);
      } else {
        AccessibilityInfo.announceForAccessibility('Đã bỏ qua buổi tập theo lịch.');
      }
    },
    onError: async (error, command) => {
      const status = error instanceof ApiError ? error.status : undefined;
      if (status === 409 || status === 403 || status === 404) {
        delete commandKeys.current[`${command.action}:${command.occurrenceId}`];
        setFailedCommand(null);
        setConfirmSkip(null);
        setActionError('Lịch hoặc quyền thực hiện đã thay đổi. Dữ liệu mới đang được tải; hãy xem lại trước khi thao tác.');
        await refreshAuthority();
      } else {
        setActionError(workoutExecutionErrorMessage(error));
        setFailedCommand(status === 0 || (status !== undefined && status >= 500) ? command : null);
      }
    },
    onSettled: () => { submitting.current = false; },
  });

  const send = (command: Command) => {
    if (submitting.current || mutation.isPending) return;
    submitting.current = true;
    setActionError(null);
    mutation.mutate(command);
  };
  const begin = (item: PlannedWorkoutOccurrence, action: 'START' | 'SKIP') => {
    if (!item.availableActions.includes(action) || submitting.current || mutation.isPending) return;
    if (failedCommand?.action === action && failedCommand.occurrenceId === item.occurrenceId) {
      send(failedCommand);
      return;
    }
    const identity = `${action}:${item.occurrenceId}`;
    const commandKey = commandKeys.current[identity] ?? createCommandKey();
    commandKeys.current[identity] = commandKey;
    send({ action, occurrenceId: item.occurrenceId, expectedOccurrenceVersion: item.occurrenceVersion, commandKey });
  };

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem lịch tập của bạn." />;

  return <ScrollView testID="student-workout-schedule-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Lịch tập dự kiến</Text>
    <WorkoutButton label="Quay lại kế hoạch" secondary onPress={() => router.back()} />
    <WorkoutButton testID="open-student-change-proposals" label="Xem đề xuất đổi hoặc hủy lịch" secondary onPress={() => router.push('/workout-change-proposals' as never)} />
    <WorkoutButton testID="open-student-recurrence" label="Xem lịch tập lặp và kết quả từng kỳ" secondary onPress={() => router.push('/planned-workout-series' as never)} />
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Cửa sổ 30 ngày</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{formatScheduleTime(range.from, null, user?.timezone)} → {formatScheduleTime(range.until, null, user?.timezone)}</Text>
      <View style={styles.row}>
        <WorkoutButton label="30 ngày trước" secondary onPress={() => { setOffset(offset - 1); setActionError(null); }} />
        <WorkoutButton label="30 ngày sau" secondary onPress={() => { setOffset(offset + 1); setActionError(null); }} />
      </View>
    </WorkoutCard>
    {query.isPending ? <WorkoutState busy title="Đang tải lịch tập" message="Vui lòng chờ trong giây lát." /> : null}
    {query.isError ? <WorkoutState testID="schedule-error" title="Không thể tải lịch tập" message={scheduleErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && items.length === 0 ? <WorkoutState testID="schedule-empty" title="Chưa có buổi tập trong khoảng này" message="Thử xem khoảng thời gian khác hoặc quay lại kế hoạch tập luyện." /> : null}
    {actionError ? <WorkoutState testID="schedule-action-error" title="Chưa thể cập nhật buổi tập" message={actionError} onRetry={failedCommand ? () => send(failedCommand) : undefined} /> : null}
    {items.map((item) => <WorkoutCard key={item.occurrenceId} testID={`schedule-occurrence-${item.occurrenceId}`}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{item.sessionSummary?.name || 'Buổi tập theo lịch'}</Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{plannedWorkoutStatusLabels[item.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Dự kiến: {formatScheduleTime(item.plannedStartAt, item.timezone, user?.timezone)}</Text>
      {item.plannedEndAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc dự kiến: {formatScheduleTime(item.plannedEndAt, item.timezone, user?.timezone)}</Text> : null}
      <Text style={[styles.body, { color: theme.textSecondary }]}>{item.planSummary?.planName || 'Không có tên kế hoạch trong bản ghi cũ'}{item.sessionSummary ? ` · Tuần ${item.sessionSummary.weekNumber}, ngày ${item.sessionSummary.dayNumber}` : ' · Không có tóm tắt buổi tập'}</Text>
      <View style={styles.row}>
        <WorkoutButton testID={`schedule-detail-${item.occurrenceId}`} label="Xem chi tiết lịch tập" secondary onPress={() => router.push(`/workouts/schedule/${item.occurrenceId}` as never)} />
        {item.availableActions.includes('START') ? <WorkoutButton testID={`schedule-start-${item.occurrenceId}`} label="Bắt đầu tập" busy={mutation.isPending} disabled={mutation.isPending} onPress={() => begin(item, 'START')} /> : null}
        {item.availableActions.includes('SKIP') ? <WorkoutButton testID={`schedule-skip-${item.occurrenceId}`} label="Bỏ qua buổi này" secondary busy={mutation.isPending} disabled={mutation.isPending} onPress={() => { setConfirmSkip(item); setActionError(null); }} /> : null}
      </View>
      {confirmSkip?.occurrenceId === item.occurrenceId ? <View testID="schedule-skip-confirmation" style={{ gap: spacing.sm }}>
        <Text accessibilityRole="alert" style={[styles.body, { color: theme.textPrimary }]}>Xác nhận bỏ qua buổi tập dự kiến này? Bạn vẫn có thể xem lịch sử sau đó.</Text>
        <View style={styles.row}>
          <WorkoutButton label="Không bỏ qua" secondary disabled={mutation.isPending} onPress={() => setConfirmSkip(null)} />
          <WorkoutButton testID="schedule-confirm-skip" label="Xác nhận bỏ qua" danger busy={mutation.isPending} disabled={mutation.isPending} onPress={() => begin(item, 'SKIP')} />
        </View>
      </View> : null}
    </WorkoutCard>)}
    {query.hasNextPage ? <WorkoutButton testID="schedule-load-more" label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm buổi tập'} secondary busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView>;
}
