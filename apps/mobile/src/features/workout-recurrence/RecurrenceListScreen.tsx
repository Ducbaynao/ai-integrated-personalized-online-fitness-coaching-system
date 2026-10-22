import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { ApiError } from '@/types/auth';
import type { RecurrenceSeries, RecurrenceStatus } from '@/types/workoutRecurrence';
import { recurrenceKeys, useStudentSeries, useTrainerSeries } from './workoutRecurrenceQueries';

export const recurrenceStatus: Record<RecurrenceStatus, string> = {
  PENDING: 'Chờ Học viên quyết định', ACTIVE: 'Đang tự lên lịch', PAUSED_SOURCE: 'Tạm dừng vì nguồn thay đổi',
  STOPPED: 'Đã dừng', REJECTED: 'Đã từ chối', WITHDRAWN: 'Đã rút',
};

export function StudentRecurrenceListScreen() {
  const { user, activeCapability } = useAuth();
  const allowed = activeCapability === 'STUDENT' && !!user?.capabilities?.hasStudentProfile;
  const query = useStudentSeries(user?.id ?? '', allowed);
  const router = useRouter();
  return <RecurrenceList allowed={allowed} title="Lịch tập lặp của tôi" items={query.data?.pages.flatMap(page => page.items) ?? []}
    pending={query.isPending} error={query.error} hasNext={!!query.hasNextPage} loadingMore={query.isFetchingNextPage}
    refetch={() => query.refetch()} loadMore={() => query.fetchNextPage()}
    open={series => router.push(`/planned-workout-series/${series.seriesId}` as never)} />;
}

export function TrainerRecurrenceListScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship;
  const studentId = relationship?.studentId ?? '';
  const trainerId = user?.id ?? '';
  const grant = sharingQuery.data?.items.find(item => item.dataScope === 'WORKOUT_PLAN');
  const allowed = activeCapability === 'TRAINER' && relationship?.trainerId === trainerId && relationship.status === 'ACTIVE'
    && grant?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const canPropose = allowed && ['CONTRIBUTE', 'MANAGE'].includes(grant?.accessLevel ?? '');
  const [status, setStatus] = useState<RecurrenceStatus | undefined>();
  const query = useTrainerSeries(trainerId, studentId, status, allowed);
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  useEffect(() => {
    if ((!allowed || denied) && studentId) client.removeQueries({ queryKey: recurrenceKeys.trainer(trainerId, studentId) });
  }, [allowed, client, denied, studentId, trainerId]);
  return <RecurrenceList allowed={allowed && !denied} title="Đề xuất lịch lặp" items={query.data?.pages.flatMap(page => page.items) ?? []}
    pending={relationshipQuery.isPending || sharingQuery.isPending || query.isPending}
    error={relationshipQuery.error || sharingQuery.error || query.error}
    hasNext={!!query.hasNextPage} loadingMore={query.isFetchingNextPage}
    refetch={() => { relationshipQuery.refetch(); sharingQuery.refetch(); query.refetch(); }}
    loadMore={() => query.fetchNextPage()}
    open={series => router.push(`/coaching/${relationshipId}/planned-workout-series/${series.seriesId}` as never)}
    create={canPropose ? () => router.push(`/coaching/${relationshipId}/planned-workout-series/new` as never) : undefined}
    status={status} setStatus={setStatus} />;
}

function RecurrenceList({ allowed, title, items, pending, error, hasNext, loadingMore, refetch, loadMore, open, create,
  status, setStatus }: { allowed: boolean; title: string; items: RecurrenceSeries[]; pending: boolean; error: unknown;
    hasNext: boolean; loadingMore: boolean; refetch: () => void; loadMore: () => void; open: (value: RecurrenceSeries) => void;
    create?: () => void; status?: RecurrenceStatus; setStatus?: (value: RecurrenceStatus | undefined) => void }) {
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const visible = useMemo(() => items.filter(item => !status || item.status === status), [items, status]);
  return <ScrollView testID="recurrence-list-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>{title}</Text>
    <WorkoutButton label="Quay lại" secondary onPress={() => router.back()} />
    {!allowed ? <WorkoutState title="Không thể xem lịch lặp" message="Quyền xem Student hoặc kế hoạch không còn hiệu lực." /> : <>
      {create ? <WorkoutButton testID="recurrence-new-proposal" label="Đề xuất series mới" onPress={create} /> : null}
      {setStatus ? <View style={styles.row}>{([undefined, 'PENDING', 'ACTIVE', 'PAUSED_SOURCE', 'STOPPED', 'REJECTED', 'WITHDRAWN'] as const)
        .map(value => <WorkoutButton key={value ?? 'ALL'} label={value ? recurrenceStatus[value] : 'Tất cả'} secondary={status !== value} onPress={() => setStatus(value)} />)}</View> : null}
      {pending ? <WorkoutState busy title="Đang tải series" message="Vui lòng chờ." /> : null}
      {error ? <WorkoutState title="Không thể tải series" message="Kiểm tra kết nối hoặc quyền truy cập." onRetry={refetch} /> : null}
      {!pending && !error && visible.length === 0 ? <WorkoutState testID="recurrence-empty" title="Chưa có series" message="Khi có lịch lặp hoặc đề xuất, dữ liệu sẽ xuất hiện ở đây." /> : null}
      {!error && visible.map(item => <WorkoutCard key={item.seriesId} testID={`recurrence-${item.seriesId}`}>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{recurrenceStatus[item.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu {item.mondayAnchorDate} · {item.timezone}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{item.items.length} buổi được chọn · block {item.planWeeks} tuần · phiên bản nguồn {item.sourcePlanVersionNumber}</Text>
        <WorkoutButton label="Xem series và từng kỳ" onPress={() => open(item)} />
      </WorkoutCard>)}
      {hasNext ? <WorkoutButton label={loadingMore ? 'Đang tải thêm' : 'Tải thêm'} disabled={loadingMore} busy={loadingMore} secondary onPress={loadMore} /> : null}
    </>}
  </ScrollView>;
}
