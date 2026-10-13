import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, useColorScheme, View } from 'react-native';
import { useFocusEffect, useRouter } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { ApiError } from '@/types/auth';
import type { PlannedWorkoutOccurrence } from '@/types/workoutSchedule';
import { calendarWindow, formatTrainerScheduleTime, plannedWorkoutStatusLabels } from './scheduleDisplay';
import { useTrainerWorkoutSchedule, workoutScheduleQueryKeys } from './workoutScheduleQueries';

const DAY = 24 * 60 * 60 * 1000;

function localIntent(value: string | null, offset: string | null, timezone: string | null) {
  if (!value) return 'Không có giờ địa phương trong bản ghi cũ';
  return `${value.replace('T', ' ')} · ${offset ? `UTC${offset}` : 'chưa có UTC offset'} · ${timezone || 'chưa có múi giờ gốc'}`;
}

function TrainerOccurrenceCard({ item, relationshipId, canContribute }: { item: PlannedWorkoutOccurrence; relationshipId: string; canContribute: boolean }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const router = useRouter();
  return <WorkoutCard testID={`trainer-calendar-occurrence-${item.occurrenceId}`}>
    <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{item.sessionSummary?.name?.trim() || 'Buổi tập theo lịch'}</Text>
    <Text style={[styles.label, { color: theme.textPrimary }]}>{plannedWorkoutStatusLabels[item.status]}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu dự kiến: {formatTrainerScheduleTime(item.plannedStartAt, item.timezone)}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ địa phương: {localIntent(item.localStart, item.startUtcOffset, item.timezone)}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc dự kiến: {item.plannedEndAt ? formatTrainerScheduleTime(item.plannedEndAt, item.timezone) : 'Chưa xác định trong bản ghi cũ'}</Text>
    {item.localEnd ? <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc địa phương: {localIntent(item.localEnd, item.endUtcOffset, item.timezone)}</Text> : null}
    <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {item.planSummary?.planName?.trim() || 'Chưa có tên kế hoạch trong bản ghi cũ'}{item.planSummary ? ` · Phiên bản ${item.planSummary.planVersionNumber}` : ''}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi: {item.sessionSummary ? `Tuần ${item.sessionSummary.weekNumber}, ngày ${item.sessionSummary.dayNumber}, thứ tự ${item.sessionSummary.sequenceNumber}` : 'Chưa có tóm tắt buổi tập trong bản ghi cũ'}</Text>
    {item.sessionSummary?.focus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {item.sessionSummary.focus}</Text> : null}
    {canContribute && item.status === 'SCHEDULED' ? <WorkoutButton testID={`trainer-propose-change-${item.occurrenceId}`} label="Đề xuất đổi hoặc hủy buổi này" secondary
      onPress={() => router.push(`/coaching/${relationshipId}/change-proposals/new?occurrenceId=${encodeURIComponent(item.occurrenceId)}` as never)} /> : null}
  </WorkoutCard>;
}

export function TrainerWorkoutScheduleScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const trainerId = user?.id ?? '';
  const scope = `${trainerId}:${relationshipId}`;
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const refetchRelationship = relationshipQuery.refetch;
  const refetchSharing = sharingQuery.refetch;
  const relationship = relationshipQuery.data?.relationship;
  const studentId = relationship?.studentId ?? '';
  const grant = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const canView = activeCapability === 'TRAINER' && relationship?.trainerId === trainerId && relationship.status === 'ACTIVE'
    && grant?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const canContribute = canView && ['CONTRIBUTE', 'MANAGE'].includes(grant?.accessLevel ?? '');
  const [verifiedScope, setVerifiedScope] = useState('');
  const [deniedScope, setDeniedScope] = useState('');
  const [anchor] = useState(() => Date.now() - 7 * DAY);
  const [offset, setOffset] = useState(0);
  const range = useMemo(() => calendarWindow(anchor, offset), [anchor, offset]);
  const verified = verifiedScope === scope && canView && deniedScope !== scope;
  const query = useTrainerWorkoutSchedule(trainerId, studentId, range.from, range.until, verified);
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];
  const total = query.data?.pages[0]?.totalItems ?? 0;

  const clearCalendar = useCallback((subjectId: string) => {
    if (trainerId && subjectId) client.removeQueries({ queryKey: workoutScheduleQueryKeys.trainer(trainerId, subjectId) });
  }, [client, trainerId]);

  const verifyAuthority = useCallback(async (isActive: () => boolean) => {
    setVerifiedScope('');
    if (activeCapability === 'TRAINER' && trainerId) {
      try {
        const [relation, sharing] = await Promise.all([refetchRelationship(), refetchSharing()]);
        if (!isActive()) return;
        const fresh = relation.data?.relationship;
        const permission = sharing.data?.items.find((entry) => entry.dataScope === 'WORKOUT_PLAN');
        const allowed = !relation.isError && !sharing.isError && fresh?.trainerId === trainerId
          && fresh.status === 'ACTIVE' && permission?.state === 'ALLOWED'
          && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(permission.accessLevel ?? '');
        if (allowed) { setDeniedScope(''); setVerifiedScope(scope); }
        else { clearCalendar(studentId); if (fresh?.studentId !== studentId) clearCalendar(fresh?.studentId ?? ''); setDeniedScope(scope); }
      } catch { if (isActive()) { clearCalendar(studentId); setDeniedScope(scope); } }
    } else { clearCalendar(studentId); setDeniedScope(scope); }
  }, [activeCapability, clearCalendar, refetchRelationship, refetchSharing, scope, studentId, trainerId]);

  // Recheck the current Coaching/Sharing boundary on every navigation focus. Cached grants do not authorize a read.
  useFocusEffect(useCallback(() => {
    let active = true;
    void verifyAuthority(() => active);
    return () => { active = false; clearCalendar(studentId); };
  }, [clearCalendar, studentId, verifyAuthority]));

  useEffect(() => {
    if (!studentId || (canView && !denied)) return;
    clearCalendar(studentId);
  }, [canView, clearCalendar, denied, studentId]);

  useEffect(() => {
    if (!denied || deniedScope === scope) return;
    let active = true;
    // Query removal is external cache cleanup; latch authority loss before another query can be enabled.
    void Promise.resolve().then(() => { if (active) setDeniedScope(scope); });
    return () => { active = false; };
  }, [denied, deniedScope, scope]);

  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên để xem lịch." />;
  if (relationshipQuery.isPending || sharingQuery.isPending || (!verified && deniedScope !== scope && canView))
    return <WorkoutState busy title="Đang kiểm tra quyền xem lịch" message="Vui lòng chờ." />;
  if (!verified || denied || relationshipQuery.isError || sharingQuery.isError)
    return <WorkoutState testID="trainer-calendar-denied" title="Không thể xem lịch tập" message="Quan hệ hoặc quyền xem kế hoạch không còn hiệu lực. Không hiển thị dữ liệu lịch đã lưu trước đó."
      onRetry={() => { setDeniedScope(''); void verifyAuthority(() => true); }} />;

  const refreshing = query.isFetching && !query.isFetchingNextPage;

  return <ScrollView testID="trainer-workout-schedule-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Lịch tập đã xác nhận</Text>
    <WorkoutButton label="Quay lại học viên" secondary onPress={() => router.back()} />
    <WorkoutButton testID="open-trainer-change-proposals" label="Xem đề xuất đổi hoặc hủy lịch" secondary onPress={() => router.push(`/coaching/${relationshipId}/change-proposals` as never)} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Lịch dự kiến của {relationship?.counterparty.displayName || 'học viên này'}. Chỉ xem; Trainer không thể bắt đầu, bỏ qua hoặc xem nhật ký tập từ đây.</Text>
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Cửa sổ 30 ngày</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Từ {formatTrainerScheduleTime(range.from, 'UTC')} đến trước {formatTrainerScheduleTime(range.until, 'UTC')}</Text>
      <View style={styles.row}>
        <WorkoutButton testID="trainer-calendar-previous" label="30 ngày trước" secondary onPress={() => setOffset((value) => value - 1)} />
        <WorkoutButton testID="trainer-calendar-next" label="30 ngày sau" secondary onPress={() => setOffset((value) => value + 1)} />
      </View>
    </WorkoutCard>
    {query.isPending || refreshing ? <WorkoutState busy title="Đang tải lịch tập" message="Vui lòng chờ." /> : null}
    {query.isError ? <WorkoutState testID="trainer-calendar-error" title="Không thể tải lịch tập" message="Không thể kết nối hoặc máy chủ đang gặp sự cố. Hãy thử lại." onRetry={denied ? undefined : () => query.refetch()} /> : null}
    {!query.isPending && !query.isError && !refreshing && items.length === 0 ? <WorkoutState testID="trainer-calendar-empty" title="Chưa có buổi tập trong khoảng này" message="Thử xem cửa sổ 30 ngày khác. Đề xuất đang chờ không phải lịch đã xác nhận." /> : null}
    {!query.isError && !refreshing && !query.isPending ? <>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Có {total} buổi trong cửa sổ này.</Text>
      {items.map((item) => <TrainerOccurrenceCard key={item.occurrenceId} item={item} relationshipId={relationshipId} canContribute={canContribute} />)}
      {query.hasNextPage ? <WorkoutButton testID="trainer-calendar-load-more" label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm buổi tập'} secondary busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
    </> : null}
  </ScrollView>;
}
