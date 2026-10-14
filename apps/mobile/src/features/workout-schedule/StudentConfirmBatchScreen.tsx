import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useWorkoutPlan, useWorkoutPlanVersion, workoutPlanQueryKeys } from '@/features/workout-plan/workoutPlanQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { ApiError } from '@/types/auth';
import type { WorkoutSession } from '@/types/workoutPlan';
import type { ConfirmWorkoutScheduleBatchRequest } from '@/types/workoutScheduleBatch';
import { isMonday, proposalTimezones, validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { workoutScheduleQueryKeys } from './workoutScheduleQueries';
import { DirectBatchEntry, suggestedSessionDate, validateDirectBatch } from './directBatchValidation';

function batchError(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Không thể kết nối. Hãy kiểm tra mạng rồi thử lại cùng yêu cầu.';
  if (error.status === 0) return 'Mất kết nối. Bạn có thể thử lại cùng yêu cầu mà không tạo thêm lịch.';
  if (error.status === 403 || error.status === 404) return 'Kế hoạch không còn khả dụng hoặc bạn không còn quyền xem.';
  if (error.status === 409) return 'Kế hoạch hoặc lịch đã thay đổi. Hãy kiểm tra lại toàn bộ đợt lịch trước khi xác nhận mới.';
  if (error.status >= 500) return 'Máy chủ đang gặp sự cố. Bạn có thể thử lại cùng yêu cầu.';
  return 'Đợt lịch chưa hợp lệ. Hãy kiểm tra các buổi được chỉ rõ bên dưới.';
}

export function StudentConfirmBatchScreen({ planId }: { planId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const planQuery = useWorkoutPlan(studentId, planId, allowed);
  const plan = planQuery.data?.plan;
  const currentVersion = planQuery.data?.currentVersion;
  const versionQuery = useWorkoutPlanVersion(studentId, planId, currentVersion?.id ?? '', allowed && Boolean(currentVersion));
  const version = versionQuery.data?.version;
  const sessions = versionQuery.data?.sessions ?? [];
  const sourceReady = plan?.status === 'ACTIVE' && plan.readContext === 'CURRENT'
    && currentVersion?.id === plan.currentVersionId && Boolean(currentVersion?.lockedAt)
    && currentVersion?.effectiveUntil === null && version?.id === currentVersion.id
    && version.current && Boolean(version.lockedAt) && version.effectiveUntil === null;
  const [timezoneSearch, setTimezoneSearch] = useState('');
  const [timezone, setTimezone] = useState('');
  const [anchor, setAnchor] = useState('');
  const [entries, setEntries] = useState<DirectBatchEntry[]>([]);
  const [preview, setPreview] = useState(false);
  const [pending, setPending] = useState<ConfirmWorkoutScheduleBatchRequest | null>(null);
  const [stale, setStale] = useState(false);
  const [concealed, setConcealed] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);
  const zones = useMemo(() => proposalTimezones(timezoneSearch), [timezoneSearch]);
  const validation = useMemo(() => validateDirectBatch(anchor, timezone, entries), [anchor, timezone, entries]);

  useEffect(() => {
    const denied = [planQuery.error, versionQuery.error].some((reason) => reason instanceof ApiError && [403, 404].includes(reason.status));
    if (!denied || !studentId || concealed) return;
    client.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
    client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
    queueMicrotask(() => setConcealed(true));
  }, [client, concealed, planQuery.error, studentId, versionQuery.error]);

  const resetReview = () => { setPreview(false); setPending(null); setStale(false); setError(''); };
  const updateEntry = (id: string, patch: Partial<DirectBatchEntry>) => {
    setEntries((current) => current.map((entry) => entry.session.id === id ? { ...entry, ...patch } : entry));
    resetReview();
  };
  const selectSession = (session: WorkoutSession) => {
    if (!['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement)) return;
    setEntries((current) => current.some((entry) => entry.session.id === session.id)
      ? current.filter((entry) => entry.session.id !== session.id)
      : [...current, { session, localStart: '', localEnd: '', startOffset: '', endOffset: '' }]);
    resetReview();
  };
  const refreshSource = async () => { await Promise.all([planQuery.refetch(), versionQuery.refetch()]); };
  const mutation = useMutation({
    mutationFn: (body: ConfirmWorkoutScheduleBatchRequest) => workoutScheduleApi.confirmBatch(body),
    onSuccess: async () => {
      setPending(null); setPreview(false); setError('');
      await client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
      await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
      AccessibilityInfo.announceForAccessibility('Đã xác nhận toàn bộ đợt lịch. Đang mở lịch tập.');
      router.replace('/workouts/schedule' as never);
    },
    onError: async (reason, body) => {
      const status = reason instanceof ApiError ? reason.status : undefined;
      const itemMessages = reason instanceof ApiError ? reason.errorResponse?.itemErrors?.map((item) => {
        const index = body.items.findIndex((entry) => entry.clientItemId === item.clientItemId);
        return `${entries[index]?.session.name ?? 'Buổi trong đợt lịch'}${item.field ? ` (${item.field})` : ''}: ${item.message}`;
      }) ?? [] : [];
      setError([batchError(reason), ...itemMessages].join('\n'));
      if (status === 403 || status === 404) {
        setPending(null); setPreview(false); setConcealed(true);
        client.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
      } else if (status === 409 || status === 400) {
        setPending(null); setPreview(false); setStale(true);
        if (status === 409) setEntries([]);
        await refreshSource();
      } else if (status !== 0 && status !== undefined && status < 500) setPending(null);
    },
    onSettled: () => { submitting.current = false; },
  });
  const confirm = () => {
    if (submitting.current || mutation.isPending || !preview || stale || !sourceReady || !plan || !version || !allowed || concealed) return;
    const problem = validateDirectBatch(anchor, timezone, entries, Date.now());
    if (problem) { setError(problem); setPreview(false); setPending(null); return; }
    const body = pending ?? {
      planId: plan.id,
      sourcePlanVersionId: version.id,
      expectedPlanAggregateVersion: plan.aggregateVersion,
      weekAnchorDate: anchor,
      timezone,
      commandKey: createCommandKey(),
      items: entries.map((entry) => ({
        clientItemId: createCommandKey(), planSessionId: entry.session.id,
        weekNumber: entry.session.weekNumber, dayNumber: entry.session.dayNumber, sequenceNumber: entry.session.sequenceNumber,
        localStart: entry.localStart, localEnd: entry.localEnd,
        startUtcOffset: entry.startOffset, endUtcOffset: entry.endOffset,
      })),
    };
    setPending(body); submitting.current = true; setError(''); mutation.mutate(body);
  };
  const review = () => {
    const problem = validateDirectBatch(anchor, timezone, entries, Date.now());
    setError(problem ?? ''); setPreview(!problem); setPending(null); setStale(false);
  };

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để tự lên lịch tập." />;
  if (concealed || [planQuery.error, versionQuery.error].some((reason) => reason instanceof ApiError && [403, 404].includes(reason.status)))
    return <WorkoutState title="Không thể mở kế hoạch" message="Kế hoạch không còn khả dụng hoặc bạn không còn quyền xem." />;
  if (planQuery.isPending || (currentVersion && versionQuery.isPending)) return <WorkoutState busy title="Đang tải kế hoạch" message="Vui lòng chờ." />;
  if (planQuery.isError || versionQuery.isError) return <WorkoutState title="Không thể tải kế hoạch" message="Kiểm tra kết nối rồi thử lại." onRetry={refreshSource} />;

  return <ScrollView testID="student-confirm-batch-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Tự lên lịch tập</Text>
    <WorkoutButton label="Quay lại kế hoạch" secondary onPress={() => router.back()} />
    {!sourceReady ? <WorkoutState title="Chưa có kế hoạch phù hợp" message="Chỉ kế hoạch ACTIVE với phiên bản đã xuất bản và đang hiệu lực mới có thể xác nhận lịch. Không dùng bản nháp hoặc phiên bản lịch sử." /> : null}
    {sourceReady && plan ? <>
      <WorkoutCard><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{plan.name}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {version?.versionNumber} · {sessions.length} buổi. Lịch chỉ được tạo sau khi bạn xác nhận toàn bộ đợt.</Text></WorkoutCard>
      <WorkoutCard><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Múi giờ của đợt lịch</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn rõ múi giờ IANA; không dùng ngầm múi giờ thiết bị hay hồ sơ.</Text>
        <TextInput testID="timezone-search" accessibilityLabel="Tìm múi giờ IANA" placeholder="Tìm, ví dụ Asia/Ho_Chi_Minh" value={timezoneSearch} onChangeText={setTimezoneSearch} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
        <Text style={[styles.label, { color: theme.textPrimary }]}>Đã chọn: {timezone || 'Chưa chọn'}</Text>
        {zones.map((zone) => <WorkoutButton key={zone} label={zone} secondary={zone !== timezone} onPress={() => { setTimezone(zone); resetReview(); }} />)}
      </WorkoutCard>
      <WorkoutCard><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Tuần bắt đầu</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn thứ Hai dạng YYYY-MM-DD. Mỗi buổi được đặt trong tuần tương ứng của kế hoạch.</Text>
        <TextInput testID="week-anchor" accessibilityLabel="Ngày thứ Hai bắt đầu đợt lịch" placeholder="YYYY-MM-DD" value={anchor} onChangeText={(value) => { setAnchor(value); resetReview(); }} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
        <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ bắt đầu của từng buổi phải ở tương lai và không quá 90 ngày tính từ lúc máy chủ xác nhận. Kiểm tra trên thiết bị chỉ để hướng dẫn trước khi gửi.</Text>
        {anchor && !isMonday(anchor) ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Ngày đầu tuần phải là thứ Hai hợp lệ.</Text> : null}
      </WorkoutCard>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Chọn buổi từ phiên bản đã xuất bản</Text>
      {sessions.length === 0 ? <WorkoutState title="Phiên bản chưa có buổi tập" message="Hãy xem lại nội dung kế hoạch." /> : null}
      {sessions.map((session) => {
        const selected = entries.find((entry) => entry.session.id === session.id);
        const eligible = ['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement);
        return <WorkoutCard key={session.id} testID={`source-session-${session.id}`}>
          <Text style={[styles.label, { color: theme.textPrimary }]}>{session.name} · Tuần {session.weekNumber}, ngày {session.dayNumber}, thứ tự {session.sequenceNumber}</Text>
          {suggestedSessionDate(anchor, session) ? <Text style={[styles.body, { color: theme.textSecondary }]}>Ngày gợi ý: {suggestedSessionDate(anchor, session)}; bạn có thể chọn ngày khác trong cùng tuần.</Text> : null}
          {session.focus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {session.focus}</Text> : null}
          {!eligible ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>{session.supervisionRequirement === 'COACH_REQUIRED' ? 'Buổi này cần huấn luyện viên giám sát; flow đầu chưa hỗ trợ tự xác nhận lịch.' : 'Thiếu thông tin giám sát hợp lệ; không thể chọn.'}</Text> : null}
          <WorkoutButton testID={`select-session-${session.id}`} label={selected ? 'Bỏ buổi này' : 'Chọn buổi này'} secondary={!selected} disabled={!eligible} onPress={() => selectSession(session)} />
          {selected ? <>
            <Text style={[styles.body, { color: theme.textSecondary }]}>Nhập rõ ngày giờ địa phương YYYY-MM-DDTHH:mm. Giờ kết thúc không suy từ thời lượng dự kiến.</Text>
            {(['Start', 'End'] as const).map((part) => {
              const local = part === 'Start' ? selected.localStart : selected.localEnd;
              const chosen = part === 'Start' ? selected.startOffset : selected.endOffset;
              const options = validProposalOffsets(timezone, local);
              return <View key={part}>
                <Text style={[styles.label, { color: theme.textPrimary }]}>{part === 'Start' ? 'Bắt đầu' : 'Kết thúc'}</Text>
                <TextInput testID={`${part.toLowerCase()}-${session.id}`} accessibilityLabel={`${part === 'Start' ? 'Bắt đầu' : 'Kết thúc'} địa phương của ${session.name}`} placeholder="YYYY-MM-DDTHH:mm" value={local}
                  onChangeText={(value) => updateEntry(session.id, part === 'Start' ? { localStart: value, startOffset: '' } : { localEnd: value, endOffset: '' })}
                  style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
                {local && options.length === 0 ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Giờ không tồn tại trong múi giờ này hoặc sai định dạng.</Text> : null}
                {options.length > 1 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ bị lặp khi đổi DST: chọn rõ một UTC offset.</Text> : null}
                <View style={styles.row}>{options.map((option) => <WorkoutButton key={option.offset} label={`UTC${option.offset}`} secondary={chosen !== option.offset}
                  onPress={() => updateEntry(session.id, part === 'Start' ? { startOffset: option.offset } : { endOffset: option.offset })} />)}</View>
              </View>;
            })}
          </> : null}
        </WorkoutCard>;
      })}
      <WorkoutButton testID="review-batch" label="Xem trước toàn bộ đợt lịch" disabled={Boolean(validation) || mutation.isPending} onPress={review} />
      {validation ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>{validation}</Text> : null}
      {preview && !validation ? <WorkoutCard testID="batch-preview">
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận cả đợt lịch</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{plan.name} · Phiên bản {version?.versionNumber} · {timezone} · Tuần bắt đầu {anchor}</Text>
        {entries.map((entry) => <Text key={entry.session.id} style={[styles.body, { color: theme.textPrimary }]}>{entry.session.name}: {entry.localStart} UTC{entry.startOffset} → {entry.localEnd} UTC{entry.endOffset}</Text>)}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Máy chủ sẽ kiểm tra quyền, phiên bản và xung đột. Hoặc toàn bộ buổi được tạo, hoặc không buổi nào được tạo.</Text>
        <WorkoutButton testID="confirm-batch" label="Xác nhận tạo toàn bộ lịch" busy={mutation.isPending} disabled={mutation.isPending || stale} onPress={confirm} />
      </WorkoutCard> : null}
      {stale ? <WorkoutState title="Dữ liệu đã thay đổi" message="Đã tải lại kế hoạch. Hãy kiểm tra từng buổi, xem trước và xác nhận lại; lệnh cũ sẽ không tự gửi lại." onRetry={() => { setStale(false); setError(''); }} /> : null}
      {error ? <WorkoutState testID="batch-error" title="Chưa thể xác nhận lịch" message={error} onRetry={pending && !stale ? confirm : undefined} /> : null}
    </> : null}
  </ScrollView>;
}
