import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, Text, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { createCommandKey } from '@/services/coachingApi';
import { workoutRecurrenceApi } from '@/services/workoutRecurrenceApi';
import { ApiError } from '@/types/auth';
import type { RecurrenceDecision, RecurrencePeriod, RecurrenceSeries, RecurrenceVersionCommand } from '@/types/workoutRecurrence';
import { recurrenceStatus } from './RecurrenceListScreen';
import { recurrenceKeys, useSeriesDetail, useSeriesPeriods } from './workoutRecurrenceQueries';

const outcomeLabels: Record<RecurrencePeriod['outcome'], string> = {
  CREATED: 'Đã tạo lịch', DST_GAP: 'Giờ địa phương không tồn tại (DST)',
  DST_AMBIGUOUS: 'Giờ địa phương trùng; cần chọn offset tường minh',
  INVALID_INTERVAL: 'Khoảng thời gian không hợp lệ', SKIPPED_OVERLAP: 'Bỏ qua vì trùng lịch',
  SKIPPED_UNKNOWN_END: 'Bỏ qua vì lịch cũ chưa biết giờ kết thúc',
  SKIPPED_DUPLICATE: 'Bỏ qua vì buổi nguồn và thời điểm đã được tạo',
};
type Action = 'ACCEPT' | 'REJECT' | 'STOP' | 'WITHDRAW';
type Command = { action: Action; seriesId: string; body: RecurrenceDecision | RecurrenceVersionCommand };

export function StudentRecurrenceDetailScreen({ seriesId }: { seriesId: string }) {
  const { user, activeCapability } = useAuth();
  return <RecurrenceDetail seriesId={seriesId} actorId={user?.id ?? ''}
    allowed={activeCapability === 'STUDENT' && !!user?.capabilities?.hasStudentProfile} />;
}

export function TrainerRecurrenceDetailScreen({ relationshipId, seriesId }: { relationshipId: string; seriesId: string }) {
  const { user, activeCapability } = useAuth();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship;
  const grant = sharingQuery.data?.items.find(item => item.dataScope === 'WORKOUT_PLAN');
  const allowed = activeCapability === 'TRAINER' && relationship?.trainerId === user?.id && relationship?.status === 'ACTIVE'
    && grant?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const canWithdraw = allowed && ['CONTRIBUTE', 'MANAGE'].includes(grant?.accessLevel ?? '');
  return <RecurrenceDetail seriesId={seriesId} actorId={user?.id ?? ''} allowed={allowed} canWithdraw={canWithdraw}
    trainerStudentId={relationship?.studentId} relationshipId={relationshipId} />;
}

function RecurrenceDetail({ seriesId, actorId, allowed, canWithdraw, trainerStudentId, relationshipId }: {
  seriesId: string; actorId: string; allowed: boolean; canWithdraw?: boolean; trainerStudentId?: string; relationshipId?: string;
}) {
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [concealed, setConcealed] = useState(false);
  const [confirm, setConfirm] = useState<Action | null>(null);
  const [pending, setPending] = useState<Command | null>(null);
  const [message, setMessage] = useState('');
  const submitting = useRef(false);
  const query = useSeriesDetail(actorId, seriesId, allowed && !concealed);
  const periods = useSeriesPeriods(actorId, seriesId, allowed && !concealed && !!query.data);
  const value = query.data;
  const denied = [query.error, periods.error].some(error => error instanceof ApiError && [403, 404].includes(error.status));
  useEffect(() => {
    if (!denied || !actorId) return;
    client.removeQueries({ queryKey: relationshipId && trainerStudentId
      ? recurrenceKeys.trainer(actorId, trainerStudentId) : recurrenceKeys.student(actorId) });
    client.removeQueries({ queryKey: recurrenceKeys.detail(actorId, seriesId) });
    queueMicrotask(() => setConcealed(true));
  }, [actorId, client, denied, relationshipId, seriesId, trainerStudentId]);
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: recurrenceKeys.detail(actorId, seriesId) });
    await client.invalidateQueries({ queryKey: relationshipId && trainerStudentId
      ? recurrenceKeys.trainer(actorId, trainerStudentId) : recurrenceKeys.student(actorId) });
  };
  const mutation = useMutation({
    mutationFn: ({ action, seriesId: id, body }: Command) => action === 'STOP'
      ? workoutRecurrenceApi.stop(id, body as RecurrenceVersionCommand)
      : action === 'WITHDRAW' ? workoutRecurrenceApi.withdraw(id, body as RecurrenceVersionCommand)
        : workoutRecurrenceApi.decide(id, body as RecurrenceDecision),
    onSuccess: async result => {
      setPending(null); setConfirm(null); setMessage('');
      client.setQueryData(recurrenceKeys.detail(actorId, seriesId), result);
      await refresh();
    },
    onError: async reason => {
      const status = reason instanceof ApiError ? reason.status : undefined;
      if (status === 409) {
        setPending(null); setConfirm(null);
        setMessage('Series đã thay đổi. Tải lại và quyết định lại trên phiên bản mới.');
        await refresh();
      } else if (status === 403 || status === 404) {
        setPending(null); setConfirm(null); setConcealed(true);
        client.removeQueries({ queryKey: recurrenceKeys.detail(actorId, seriesId) });
        if (trainerStudentId) client.removeQueries({ queryKey: recurrenceKeys.trainer(actorId, trainerStudentId) });
        setMessage('Không còn quyền xem series này.');
      } else {
        setMessage('Không thể gửi. Nếu kết quả mạng chưa rõ, thử lại cùng yêu cầu.');
        if (status !== 0 && status !== undefined && status < 500) setPending(null);
      }
    },
    onSettled: () => { submitting.current = false; },
  });
  const send = () => {
    if (!value || !confirm || submitting.current || mutation.isPending || concealed) return;
    const command = pending?.action === confirm ? pending : {
      seriesId, action: confirm,
      body: confirm === 'ACCEPT' || confirm === 'REJECT'
        ? { expectedSeriesVersion: value.seriesVersion, decision: confirm, commandKey: createCommandKey() }
        : { expectedSeriesVersion: value.seriesVersion, commandKey: createCommandKey() },
    };
    setPending(command); setMessage(''); submitting.current = true; mutation.mutate(command);
  };
  const open = (action: Action) => { setPending(null); setConfirm(action); setMessage(''); };
  const series = value as RecurrenceSeries | undefined;
  return <ScrollView testID="recurrence-detail-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết lịch tập lặp</Text>
    <WorkoutButton label="Quay lại" secondary onPress={() => router.back()} />
    {!allowed || concealed ? <WorkoutState title="Không thể xem series" message="Quyền xem hiện hành không còn hiệu lực." /> : <>
      {query.isPending ? <WorkoutState busy title="Đang tải series" message="Vui lòng chờ." /> : null}
      {query.isError ? <WorkoutState title="Không thể tải series" message="Kiểm tra kết nối hoặc quyền truy cập." onRetry={() => query.refetch()} /> : null}
      {series && !query.isError ? <>
        <WorkoutCard>
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{recurrenceStatus[series.status]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Thứ Hai bắt đầu: {series.mondayAnchorDate} · {series.timezone}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Lặp block {series.planWeeks} tuần không giới hạn; chỉ các buổi đã chọn được tạo. Nguồn phiên bản {series.sourcePlanVersionNumber}.</Text>
          {series.items.map(item => <Text key={item.planSessionId} style={[styles.body, { color: theme.textPrimary }]}>
            Tuần {item.weekNumber}, ngày {item.dayNumber}, thứ tự {item.sequenceNumber}: {item.firstLocalStart} → {item.firstLocalEnd}
          </Text>)}
        </WorkoutCard>
        {!relationshipId && series.status === 'PENDING' ? <View style={styles.row}>
          <WorkoutButton testID="series-accept" label="Chấp nhận toàn series" onPress={() => open('ACCEPT')} />
          <WorkoutButton testID="series-reject" label="Từ chối" secondary onPress={() => open('REJECT')} />
        </View> : null}
        {!relationshipId && ['ACTIVE', 'PAUSED_SOURCE'].includes(series.status)
          ? <WorkoutButton testID="series-stop" label="Dừng tạo kỳ mới" danger onPress={() => open('STOP')} /> : null}
        {!relationshipId && series.status === 'PAUSED_SOURCE' ? <WorkoutButton testID="series-reconfirm-source"
          label="Xem kế hoạch hiện hành để xác nhận series mới" secondary
          onPress={() => router.push(`/workout-plans/${series.planId}/series` as never)} /> : null}
        {relationshipId && canWithdraw && series.status === 'PENDING'
          ? <WorkoutButton testID="series-withdraw" label="Rút đề xuất" secondary onPress={() => open('WITHDRAW')} /> : null}
        {confirm ? <WorkoutCard testID="series-command-confirmation">
          <Text accessibilityRole="alert" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận {confirm === 'ACCEPT' ? 'chấp nhận series' : confirm === 'REJECT' ? 'từ chối' : confirm === 'STOP' ? 'dừng series' : 'rút đề xuất'}?</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Backend kiểm tra lại quyền và phiên bản. Kỳ đã tạo vẫn là lịch sử; không tự tạo Appointment.</Text>
          <View style={styles.row}><WorkoutButton label="Xem lại" secondary onPress={() => { setConfirm(null); setPending(null); }} />
            <WorkoutButton testID="series-confirm-command" label="Xác nhận" busy={mutation.isPending} disabled={mutation.isPending} onPress={send} /></View>
        </WorkoutCard> : null}
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Kết quả từng kỳ</Text>
        {periods.isPending ? <WorkoutState busy title="Đang tải từng kỳ" message="Vui lòng chờ." /> : null}
        {periods.isError ? <WorkoutState title="Không thể tải từng kỳ" message="Kiểm tra kết nối hoặc quyền truy cập." onRetry={() => periods.refetch()} /> : null}
        {!periods.isPending && !periods.isError && !periods.data?.pages.some(page => page.items.length) ? <WorkoutState title="Chưa có kỳ được xử lý" message="Job hằng ngày chỉ xét giờ bắt đầu trong 30 ngày tới." /> : null}
        {periods.data?.pages.flatMap(page => page.items).map(period => <WorkoutCard key={period.periodId} testID={`series-period-${period.periodId}`}>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Block {period.blockIteration + 1} · {outcomeLabels[period.outcome]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>{period.localStart} → {period.localEnd} · {period.timezone}</Text>
          {period.startUtcOffset ? <Text style={[styles.body, { color: theme.textSecondary }]}>UTC {period.startUtcOffset} → {period.endUtcOffset}</Text> : null}
          {period.occurrenceId && !relationshipId ? <WorkoutButton label="Xem buổi đã tạo" secondary onPress={() => router.push(`/workouts/schedule/${period.occurrenceId}` as never)} /> : null}
          {!relationshipId && ['DST_GAP', 'DST_AMBIGUOUS'].includes(period.outcome) ? <WorkoutButton
            testID={`series-manual-time-${period.periodId}`} label="Tự chọn giờ và UTC offset cho buổi này" secondary
            onPress={() => router.push(`/workout-plans/${series.planId}/schedule` as never)} /> : null}
        </WorkoutCard>)}
        {periods.hasNextPage ? <WorkoutButton label={periods.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm kỳ'} secondary
          busy={periods.isFetchingNextPage} disabled={periods.isFetchingNextPage} onPress={() => periods.fetchNextPage()} /> : null}
      </> : null}
    </>}
    {message ? <WorkoutState testID="series-command-error" title="Cần kiểm tra lại" message={message} onRetry={pending ? send : undefined} /> : null}
  </ScrollView>;
}
