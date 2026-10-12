import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { useCurrentWorkoutPlan, useWorkoutPlanVersion, workoutPlanQueryKeys } from '@/features/workout-plan/workoutPlanQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import { ApiError } from '@/types/auth';
import type { WorkoutSession } from '@/types/workoutPlan';
import type { CreateWorkoutScheduleProposalRequest } from '@/types/workoutScheduleProposal';
import { proposalErrorMessage } from './proposalPresentation';
import { isInSessionWeek, isMonday, proposalTimezones, validProposalOffsets } from './proposalTime';
import { workoutScheduleProposalQueryKeys } from './workoutScheduleProposalQueries';

type Entry = { session: WorkoutSession; localStart: string; localEnd: string; startOffset: string; endOffset: string };
type Pending = { body: CreateWorkoutScheduleProposalRequest; studentId: string };

export function TrainerCreateScheduleProposalScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship;
  const studentId = relationship?.studentId ?? '';
  const trainerId = user?.id ?? '';
  const grant = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const allowed = activeCapability === 'TRAINER' && relationship?.trainerId === trainerId && relationship.status === 'ACTIVE'
    && grant?.state === 'ALLOWED' && ['CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const [concealed, setConcealed] = useState(false);
  const planQuery = useCurrentWorkoutPlan(studentId, allowed && !concealed, studentId);
  const plan = planQuery.data?.plan;
  const versionId = plan?.currentVersionId ?? '';
  const versionQuery = useWorkoutPlanVersion(studentId, plan?.id ?? '', versionId, allowed && !concealed && plan?.status === 'ACTIVE');
  const version = versionQuery.data?.version;
  const sessions = versionQuery.data?.sessions ?? [];
  const sourceReady = plan?.status === 'ACTIVE' && plan.currentVersionLockedAt && version?.id === versionId
    && version.current && version.lockedAt && version.effectiveUntil === null;
  const [timezoneSearch, setTimezoneSearch] = useState('');
  const [timezone, setTimezone] = useState('');
  const [anchor, setAnchor] = useState('');
  const [entries, setEntries] = useState<Entry[]>([]);
  const [preview, setPreview] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [error, setError] = useState('');
  const [stale, setStale] = useState(false);
  const submitting = useRef(false);
  const zones = useMemo(() => proposalTimezones(timezoneSearch), [timezoneSearch]);

  useEffect(() => {
    if (concealed || !studentId || (allowed && !(planQuery.error instanceof ApiError && [403, 404].includes(planQuery.error.status))
      && !(versionQuery.error instanceof ApiError && [403, 404].includes(versionQuery.error.status)))) return;
    client.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
    client.removeQueries({ queryKey: workoutScheduleProposalQueryKeys.trainer(trainerId, studentId) });
    queueMicrotask(() => setConcealed(true));
  }, [allowed, client, concealed, planQuery.error, studentId, trainerId, versionQuery.error]);

  const resetReview = () => { setPreview(false); setPending(null); setError(''); setStale(false); };
  const updateEntry = (id: string, patch: Partial<Entry>) => {
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

  const validation = useMemo(() => {
    if (!sourceReady || !timezone || !isMonday(anchor) || entries.length === 0) return 'Chọn kế hoạch đang hiệu lực, múi giờ IANA, thứ Hai đầu tuần và ít nhất một buổi.';
    const intervals: { from: number; until: number }[] = [];
    for (const entry of entries) {
      const { session } = entry;
      if (!['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement)) return `Buổi ${session.name} chưa được phép đề xuất trong luồng này.`;
      if (!isInSessionWeek(anchor, session.weekNumber, entry.localStart)) return `Ngày bắt đầu của ${session.name} phải nằm trong tuần ${session.weekNumber} tính từ thứ Hai đã chọn.`;
      const starts = validProposalOffsets(timezone, entry.localStart);
      const ends = validProposalOffsets(timezone, entry.localEnd);
      if (!starts.length || !ends.length) return `Giờ của ${session.name} không tồn tại trong múi giờ đã chọn hoặc chưa đúng định dạng YYYY-MM-DDTHH:mm.`;
      const start = starts.find((choice) => choice.offset === entry.startOffset);
      const end = ends.find((choice) => choice.offset === entry.endOffset);
      if (!start || !end) return `Chọn tường minh UTC offset hợp lệ cho cả bắt đầu và kết thúc của ${session.name}.`;
      if (end.instant <= start.instant) return `Giờ kết thúc của ${session.name} phải sau giờ bắt đầu.`;
      if (intervals.some((other) => start.instant < other.until && other.from < end.instant)) return 'Các buổi trong cùng đề xuất không được chồng thời gian.';
      intervals.push({ from: start.instant, until: end.instant });
    }
    return null;
  }, [anchor, entries, sourceReady, timezone]);

  const windowError = () => {
    const now = Date.now();
    for (const entry of entries) {
      const start = validProposalOffsets(timezone, entry.localStart).find((choice) => choice.offset === entry.startOffset);
      if (start && (start.instant <= now || start.instant > now + 90 * 86_400_000))
        return `Giờ bắt đầu của ${entry.session.name} phải ở tương lai và trong 90 ngày tới (máy chủ sẽ kiểm tra lại).`;
    }
    return null;
  };
  const review = () => { const problem = validation ?? windowError(); setError(problem ?? ''); setPreview(!problem); setPending(null); };
  const create = () => {
    if (submitting.current || mutation.isPending || !preview || validation || !plan || !version || !allowed || stale) return;
    const timeProblem = windowError();
    if (timeProblem) { setError(timeProblem); setPreview(false); setPending(null); return; }
    const command = pending ?? { studentId, body: {
      planId: plan.id, sourcePlanVersionId: version.id, expectedPlanAggregateVersion: plan.aggregateVersion,
      weekAnchorDate: anchor, timezone, commandKey: createCommandKey(),
      items: entries.map((entry) => ({ clientItemId: createCommandKey(), planSessionId: entry.session.id,
        weekNumber: entry.session.weekNumber, dayNumber: entry.session.dayNumber, sequenceNumber: entry.session.sequenceNumber,
        localStart: entry.localStart, localEnd: entry.localEnd, startUtcOffset: entry.startOffset, endUtcOffset: entry.endOffset })),
    } };
    setPending(command); submitting.current = true; setError(''); mutation.mutate(command);
  };
  const mutation = useMutation({
    mutationFn: (command: Pending) => workoutScheduleProposalApi.create(command.studentId, command.body),
    onSuccess: async (result) => {
      setPending(null); setPreview(false);
      await client.invalidateQueries({ queryKey: workoutScheduleProposalQueryKeys.trainer(trainerId, studentId) });
      AccessibilityInfo.announceForAccessibility('Đã tạo đề xuất. Học viên sẽ quyết định toàn bộ lịch.');
      router.replace(`/coaching/${relationshipId}/schedule-proposals/${result.proposal.proposalId}` as never);
    },
    onError: async (reason) => {
      const itemMessages = reason instanceof ApiError ? reason.errorResponse?.itemErrors?.map((item) => {
        const index = pending?.body.items.findIndex((entry) => entry.clientItemId === item.clientItemId) ?? -1;
        const name = index >= 0 ? entries[index]?.session.name : undefined;
        return `${name || 'Buổi trong đề xuất'}${item.field ? ` (${item.field})` : ''}: ${item.message}`;
      }) ?? [] : [];
      setError([proposalErrorMessage(reason), ...itemMessages].join('\n'));
      if (reason instanceof ApiError && reason.status === 409) {
        setPending(null); setPreview(false); setStale(true);
        await Promise.all([planQuery.refetch(), versionQuery.refetch()]);
      } else if (reason instanceof ApiError && [403, 404].includes(reason.status)) {
        setPending(null); setPreview(false); setConcealed(true);
        client.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        client.removeQueries({ queryKey: workoutScheduleProposalQueryKeys.trainer(trainerId, studentId) });
      } else if (!(reason instanceof ApiError) || (reason.status !== 0 && reason.status < 500)) setPending(null);
    },
    onSettled: () => { submitting.current = false; },
  });

  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên." />;
  if (relationshipQuery.isPending || sharingQuery.isPending) return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." />;
  if (!allowed || concealed || relationshipQuery.isError || sharingQuery.isError) return <WorkoutState title="Không thể tạo đề xuất" message="Quan hệ hoặc quyền Đóng góp kế hoạch không còn hiệu lực." onRetry={() => { relationshipQuery.refetch(); sharingQuery.refetch(); }} />;
  return <ScrollView testID="trainer-create-proposal" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Tạo đề xuất lịch tập</Text>
    <WorkoutButton label="Quay lại đề xuất" secondary onPress={() => router.back()} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Học viên sẽ chấp nhận hoặc từ chối toàn bộ. Tạo đề xuất chưa tạo buổi tập hay giữ chỗ trên lịch.</Text>
    {planQuery.isPending || versionQuery.isPending ? <WorkoutState busy title="Đang tải kế hoạch" message="Vui lòng chờ." /> : null}
    {planQuery.isError || versionQuery.isError ? <WorkoutState title="Không thể tải kế hoạch" message={proposalErrorMessage(planQuery.error ?? versionQuery.error)} onRetry={() => { planQuery.refetch(); versionQuery.refetch(); }} /> : null}
    {!planQuery.isPending && !versionQuery.isPending && !sourceReady ? <WorkoutState title="Chưa có phiên bản phù hợp" message="Cần kế hoạch ACTIVE với phiên bản đã xuất bản và đang hiệu lực. Không thể dùng bản nháp hoặc phiên bản cũ." /> : null}
    {sourceReady && plan ? <WorkoutCard>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{plan.name}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {version?.versionNumber} · {sessions.length} buổi · Học viên: {relationship?.counterparty.displayName}</Text>
    </WorkoutCard> : null}
    {sourceReady ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Múi giờ của đề xuất</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Huấn luyện viên phải chọn rõ múi giờ IANA. Đây không phải múi giờ hồ sơ học viên hay thiết bị.</Text>
        <TextInput testID="timezone-search" accessibilityLabel="Tìm múi giờ IANA" placeholder="Tìm, ví dụ Asia/Ho_Chi_Minh" value={timezoneSearch} onChangeText={setTimezoneSearch} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
        <Text style={[styles.label, { color: theme.textPrimary }]}>Đã chọn: {timezone || 'Chưa chọn'}</Text>
        {zones.map((zone) => <WorkoutButton key={zone} label={zone} secondary={zone !== timezone} onPress={() => { setTimezone(zone); resetReview(); }} />)}
      </WorkoutCard>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Tuần bắt đầu</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Nhập ngày thứ Hai dạng YYYY-MM-DD. Tuần của mỗi buổi được tính từ ngày này.</Text>
        <TextInput testID="week-anchor" accessibilityLabel="Ngày thứ Hai bắt đầu" placeholder="2026-10-12" value={anchor} onChangeText={(value) => { setAnchor(value); resetReview(); }} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
      </WorkoutCard>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Chọn buổi từ phiên bản đã xuất bản</Text>
      {sessions.map((session) => {
        const selected = entries.find((entry) => entry.session.id === session.id);
        const eligible = ['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement);
        return <WorkoutCard key={session.id} testID={`source-session-${session.id}`}>
          <Text style={[styles.label, { color: theme.textPrimary }]}>{session.name} · Tuần {session.weekNumber}, ngày {session.dayNumber}, thứ tự {session.sequenceNumber}</Text>
          {session.focus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {session.focus}</Text> : null}
          {!eligible ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>{session.supervisionRequirement === 'COACH_REQUIRED' ? 'Cần huấn luyện viên giám sát; flow đầu chưa hỗ trợ đề xuất buổi này.' : 'Thiếu metadata giám sát hợp lệ; không thể chọn.'}</Text> : null}
          <WorkoutButton testID={`select-session-${session.id}`} label={selected ? 'Bỏ buổi này' : 'Chọn buổi này'} secondary={!selected} disabled={!eligible} onPress={() => selectSession(session)} />
          {selected ? <>
            <Text style={[styles.body, { color: theme.textSecondary }]}>Nhập tường minh ngày giờ địa phương dạng YYYY-MM-DDTHH:mm. Không suy thời lượng từ kế hoạch.</Text>
            {(['Start', 'End'] as const).map((part) => {
              const local = part === 'Start' ? selected.localStart : selected.localEnd;
              const options = validProposalOffsets(timezone, local);
              const chosen = part === 'Start' ? selected.startOffset : selected.endOffset;
              return <View key={part}>
                <Text style={[styles.label, { color: theme.textPrimary }]}>{part === 'Start' ? 'Bắt đầu' : 'Kết thúc'}</Text>
                <TextInput testID={`${part.toLowerCase()}-${session.id}`} accessibilityLabel={`${part === 'Start' ? 'Bắt đầu' : 'Kết thúc'} địa phương của ${session.name}`}
                  placeholder="2026-10-12T18:30" value={local} onChangeText={(value) => updateEntry(session.id, part === 'Start' ? { localStart: value, startOffset: '' } : { localEnd: value, endOffset: '' })}
                  style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
                {local && options.length === 0 ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Giờ không tồn tại trong múi giờ này hoặc sai định dạng.</Text> : null}
                {options.length > 1 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ bị lặp khi đổi DST: chọn một offset rõ ràng.</Text> : null}
                <View style={styles.row}>{options.map((option) => <WorkoutButton key={option.offset} label={`UTC${option.offset}`} secondary={chosen !== option.offset}
                  onPress={() => updateEntry(session.id, part === 'Start' ? { startOffset: option.offset } : { endOffset: option.offset })} />)}</View>
              </View>;
            })}
          </> : null}
        </WorkoutCard>;
      })}
      <WorkoutButton testID="review-proposal" label="Xem trước toàn bộ đề xuất" disabled={Boolean(validation) || mutation.isPending} onPress={review} />
      {validation ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>{validation}</Text> : null}
      {preview && !validation ? <WorkoutCard testID="proposal-preview">
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận tạo đề xuất</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {plan?.name} · Phiên bản {version?.versionNumber} · Múi giờ Trainer chọn: {timezone} · Tuần bắt đầu: {anchor}</Text>
        {entries.map((entry) => <Text key={entry.session.id} style={[styles.body, { color: theme.textPrimary }]}>{entry.session.name}: {entry.localStart} UTC{entry.startOffset} → {entry.localEnd} UTC{entry.endOffset}</Text>)}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Học viên sẽ xem múi giờ, thời gian từng buổi rồi chấp nhận hoặc từ chối toàn bộ. Không buổi nào được tạo khi chỉ gửi đề xuất.</Text>
        <WorkoutButton testID="confirm-create-proposal" label="Gửi đề xuất cho học viên" busy={mutation.isPending} disabled={mutation.isPending || stale} onPress={create} />
      </WorkoutCard> : null}
      {stale ? <WorkoutState title="Dữ liệu đã thay đổi" message="Đã tải lại kế hoạch. Hãy kiểm tra thời gian, xem trước và xác nhận lại; lệnh cũ sẽ không được gửi lại." onRetry={() => { setStale(false); setError(''); }} /> : null}
      {error ? <WorkoutState testID="create-proposal-error" title="Chưa thể tạo đề xuất" message={error} onRetry={pending && !stale ? create : undefined} /> : null}
    </> : null}
  </ScrollView>;
}
