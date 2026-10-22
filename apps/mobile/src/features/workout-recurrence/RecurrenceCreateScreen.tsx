import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { useCurrentWorkoutPlan, useWorkoutPlan, useWorkoutPlanVersion, workoutPlanQueryKeys } from '@/features/workout-plan/workoutPlanQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { proposalTimezones } from '@/features/workout-schedule-proposals/proposalTime';
import { suggestedSessionDate } from '@/features/workout-schedule/directBatchValidation';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { createCommandKey } from '@/services/coachingApi';
import { workoutRecurrenceApi } from '@/services/workoutRecurrenceApi';
import { ApiError } from '@/types/auth';
import type { RecurrenceCreate } from '@/types/workoutRecurrence';
import type { WorkoutSession } from '@/types/workoutPlan';
import { buildRecurrenceRequest, recurrencePreview, type RecurrenceEntry } from './recurrenceForm';
import { recurrenceKeys } from './workoutRecurrenceQueries';

export function StudentRecurrenceCreateScreen({ planId }: { planId: string }) {
  const { user, activeCapability } = useAuth();
  const allowed = activeCapability === 'STUDENT' && !!user?.capabilities?.hasStudentProfile;
  const planQuery = useWorkoutPlan(user?.id ?? '', planId, allowed);
  const plan = planQuery.data?.plan;
  const version = planQuery.data?.currentVersion;
  return <RecurrenceCreateForm key={`${user?.id}:${planId}:${version?.id}`} studentId={user?.id ?? ''}
    planId={planId} versionId={version?.id ?? ''} aggregateVersion={plan?.aggregateVersion ?? -1}
    sourceReady={allowed && plan?.status === 'ACTIVE' && plan.readContext === 'CURRENT'
      && !!version?.lockedAt && version.effectiveUntil === null}
    parentBusy={planQuery.isPending} parentError={planQuery.isError} refreshParent={() => planQuery.refetch()} />;
}

export function TrainerRecurrenceCreateScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship;
  const studentId = relationship?.studentId ?? '';
  const grant = sharingQuery.data?.items.find(item => item.dataScope === 'WORKOUT_PLAN');
  const allowed = activeCapability === 'TRAINER' && relationship?.trainerId === user?.id
    && relationship?.status === 'ACTIVE' && grant?.state === 'ALLOWED'
    && ['CONTRIBUTE', 'MANAGE'].includes(grant.accessLevel ?? '');
  const planQuery = useCurrentWorkoutPlan(studentId, allowed, studentId);
  const plan = planQuery.data?.plan;
  return <RecurrenceCreateForm key={`${user?.id}:${studentId}:${plan?.currentVersionId}`} studentId={studentId}
    planId={plan?.id ?? ''} versionId={plan?.currentVersionId ?? ''} aggregateVersion={plan?.aggregateVersion ?? -1}
    relationshipId={relationshipId} sourceReady={allowed && plan?.status === 'ACTIVE'
      && !!plan.currentVersionLockedAt && plan.currentVersionEffectiveFrom !== null}
    parentBusy={relationshipQuery.isPending || sharingQuery.isPending || planQuery.isPending}
    parentError={relationshipQuery.isError || sharingQuery.isError || planQuery.isError}
    refreshParent={() => { relationshipQuery.refetch(); sharingQuery.refetch(); planQuery.refetch(); }} />;
}

function RecurrenceCreateForm({ studentId, planId, versionId, aggregateVersion, relationshipId,
  sourceReady, parentBusy, parentError, refreshParent }: {
  studentId: string; planId: string; versionId: string; aggregateVersion: number; relationshipId?: string;
  sourceReady: boolean; parentBusy: boolean; parentError: boolean; refreshParent: () => void;
}) {
  const { user } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const versionQuery = useWorkoutPlanVersion(studentId, planId, versionId, sourceReady);
  const version = versionQuery.data?.version;
  const sessions = versionQuery.data?.sessions ?? [];
  const published = sourceReady && version?.id === versionId && version.current
    && !!version.lockedAt && version.effectiveUntil === null;
  const [anchor, setAnchor] = useState('');
  const [timezoneSearch, setTimezoneSearch] = useState('');
  const [timezone, setTimezone] = useState('');
  const [entries, setEntries] = useState<RecurrenceEntry[]>([]);
  const [review, setReview] = useState(false);
  const [pending, setPending] = useState<RecurrenceCreate | null>(null);
  const [error, setError] = useState('');
  const [concealed, setConcealed] = useState(false);
  const submitting = useRef(false);
  const preview = useMemo(() => recurrencePreview(anchor, timezone, entries), [anchor, timezone, entries]);
  const zones = useMemo(() => proposalTimezones(timezoneSearch), [timezoneSearch]);
  const denied = versionQuery.error instanceof ApiError && [403, 404].includes(versionQuery.error.status);
  useEffect(() => {
    if (!denied || !studentId) return;
    client.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
    client.removeQueries({ queryKey: relationshipId ? recurrenceKeys.trainer(user?.id ?? '', studentId) : recurrenceKeys.student(studentId) });
    queueMicrotask(() => setConcealed(true));
  }, [client, denied, relationshipId, studentId, user?.id]);
  const reset = () => { setReview(false); setPending(null); setError(''); };
  const select = (session: WorkoutSession) => {
    if (!['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement)) return;
    setEntries(current => current.some(entry => entry.session.id === session.id)
      ? current.filter(entry => entry.session.id !== session.id)
      : [...current, { session, firstLocalStart: '', firstLocalEnd: '' }]);
    reset();
  };
  const update = (id: string, field: 'firstLocalStart' | 'firstLocalEnd', value: string) => {
    setEntries(current => current.map(entry => entry.session.id === id ? { ...entry, [field]: value } : entry));
    reset();
  };
  const mutation = useMutation({
    mutationFn: (body: RecurrenceCreate) => relationshipId
      ? workoutRecurrenceApi.propose(studentId, body) : workoutRecurrenceApi.confirm(body),
    onSuccess: async result => {
      setPending(null); setError(''); setReview(false);
      await client.invalidateQueries({ queryKey: relationshipId
        ? recurrenceKeys.trainer(user?.id ?? '', studentId) : recurrenceKeys.student(studentId) });
      router.replace((relationshipId
        ? `/coaching/${relationshipId}/planned-workout-series/${result.seriesId}`
        : `/planned-workout-series/${result.seriesId}`) as never);
    },
    onError: async reason => {
      const status = reason instanceof ApiError ? reason.status : undefined;
      if (status === 409) {
        setPending(null); setReview(false);
        setError('Nguồn kế hoạch hoặc lịch đã thay đổi. Hãy tải lại, xem trước và xác nhận mới.');
        refreshParent(); await versionQuery.refetch();
      } else if (status === 403 || status === 404) {
        setPending(null); setReview(false); setConcealed(true);
        client.removeQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        client.removeQueries({ queryKey: relationshipId
          ? recurrenceKeys.trainer(user?.id ?? '', studentId) : recurrenceKeys.student(studentId) });
        setError('Không còn quyền xem hoặc sử dụng kế hoạch này.');
      } else {
        setError('Không thể gửi. Nếu kết quả mạng chưa rõ, thử lại cùng yêu cầu để tránh tạo lặp.');
        if (status !== 0 && status !== undefined && status < 500) setPending(null);
      }
    },
    onSettled: () => { submitting.current = false; },
  });
  const submit = () => {
    if (!published || preview.error || submitting.current || mutation.isPending || concealed) return;
    const body = pending ?? buildRecurrenceRequest(planId, versionId, aggregateVersion, anchor, timezone, entries, createCommandKey());
    setPending(body); submitting.current = true; setError(''); mutation.mutate(body);
  };

  return <ScrollView testID="recurrence-create-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>{relationshipId ? 'Đề xuất lịch lặp' : 'Tự động lên lịch tập'}</Text>
    <WorkoutButton label="Quay lại" secondary onPress={() => router.back()} />
    {parentBusy || versionQuery.isPending && sourceReady ? <WorkoutState busy title="Đang tải kế hoạch" message="Vui lòng chờ." /> : null}
    {parentError || versionQuery.isError && !concealed ? <WorkoutState title="Không thể tải kế hoạch" message="Kiểm tra kết nối hoặc quyền xem." onRetry={() => { refreshParent(); versionQuery.refetch(); }} /> : null}
    {!published || concealed ? <WorkoutState title="Chưa thể tạo series" message={concealed ? 'Quyền xem kế hoạch đã thay đổi.' : 'Cần kế hoạch ACTIVE và phiên bản đã xuất bản hiện hành cùng quyền phù hợp.'} /> : <>
      <WorkoutCard>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Phiên bản nguồn {version.versionNumber}. Series lặp toàn bộ block {Math.max(0, ...sessions.map(s => s.weekNumber))} tuần, kể cả tuần không chọn buổi; chỉ tạo buổi đã chọn. Tiếp tục đến khi Học viên dừng hoặc nguồn bị tạm dừng.</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Job chỉ xét kỳ bắt đầu trong 30 ngày tới. DST thiếu/nhập nhằng và xung đột sẽ được ghi lại để xem, không tự đoán offset hay lịch hẹn huấn luyện.</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>Thứ Hai bắt đầu series (YYYY-MM-DD)</Text>
        <TextInput testID="series-anchor" accessibilityLabel="Thứ Hai bắt đầu series" value={anchor} onChangeText={value => { setAnchor(value); reset(); }} placeholder="2026-11-02" style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        <Text style={[styles.label, { color: theme.textPrimary }]}>Tìm múi giờ IANA</Text>
        <TextInput testID="series-timezone-search" accessibilityLabel="Tìm múi giờ IANA" value={timezoneSearch} onChangeText={setTimezoneSearch} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        <Text style={[styles.body, { color: theme.textPrimary }]}>Múi giờ đã chọn: {timezone || 'Chưa chọn'}</Text>
        <View style={styles.row}>{zones.slice(0, 12).map(zone => <WorkoutButton key={zone} label={zone} secondary={zone !== timezone} onPress={() => { setTimezone(zone); reset(); }} />)}</View>
      </WorkoutCard>
      {sessions.map(session => <WorkoutCard key={session.id} testID={`series-session-${session.id}`}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{session.name} · Tuần {session.weekNumber}, ngày {session.dayNumber}, thứ tự {session.sequenceNumber}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{session.focus || 'Chưa có trọng tâm'} · {session.supervisionRequirement}</Text>
        {session.supervisionRequirement === 'COACH_REQUIRED' ? <Text style={[styles.body, { color: theme.warningText }]}>Cần huấn luyện viên: không tự tạo kỳ hoặc Appointment trong luồng này.</Text>
          : <WorkoutButton label={entries.some(entry => entry.session.id === session.id) ? 'Bỏ chọn buổi' : 'Chọn buổi'}
              secondary onPress={() => select(session)} />}
        {entries.filter(entry => entry.session.id === session.id).map(entry => <View key={session.id}>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Ngày dự kiến theo tọa độ: {suggestedSessionDate(anchor, session) || 'Chọn thứ Hai hợp lệ'}. Nhập giờ địa phương đầy đủ; không suy kết thúc từ mẫu.</Text>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Bắt đầu (YYYY-MM-DDTHH:mm)</Text>
          <TextInput testID={`series-start-${session.id}`} accessibilityLabel={`Giờ bắt đầu ${session.name}`} value={entry.firstLocalStart}
            onChangeText={value => update(session.id, 'firstLocalStart', value)} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
          <Text style={[styles.label, { color: theme.textPrimary }]}>Kết thúc (YYYY-MM-DDTHH:mm)</Text>
          <TextInput testID={`series-end-${session.id}`} accessibilityLabel={`Giờ kết thúc ${session.name}`} value={entry.firstLocalEnd}
            onChangeText={value => update(session.id, 'firstLocalEnd', value)} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        </View>)}
      </WorkoutCard>)}
      {preview.error ? <WorkoutState title="Cần kiểm tra biểu mẫu" message={preview.error} /> : null}
      {!review ? <WorkoutButton testID="series-preview" label="Xem trước toàn series" disabled={!!preview.error} onPress={() => setReview(true)} /> : <WorkoutCard testID="series-preview-card">
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận toàn series</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Bắt đầu {anchor} · {timezone} · {entries.length} buổi được chọn. {relationshipId ? 'Chỉ tạo đề xuất PENDING; Học viên quyết định sau.' : 'Chỉ các kỳ hợp lệ trong cửa sổ 30 ngày mới được tạo.'}</Text>
        {entries.map(entry => <Text key={entry.session.id} style={[styles.body, { color: theme.textSecondary }]}>{entry.session.name}: {entry.firstLocalStart} → {entry.firstLocalEnd}</Text>)}
        {preview.warnings.map(warning => <Text key={warning} style={[styles.body, { color: theme.warningText }]}>{warning}</Text>)}
        <View style={styles.row}><WorkoutButton label="Sửa lại" secondary onPress={() => { setReview(false); setPending(null); }} />
          <WorkoutButton testID="series-submit" label={relationshipId ? 'Gửi đề xuất' : 'Xác nhận series'} busy={mutation.isPending}
            disabled={mutation.isPending} onPress={submit} /></View>
      </WorkoutCard>}
    </>}
    {error ? <WorkoutState testID="series-error" title="Chưa thể hoàn tất" message={error} onRetry={pending ? submit : undefined} /> : null}
  </ScrollView>;
}
