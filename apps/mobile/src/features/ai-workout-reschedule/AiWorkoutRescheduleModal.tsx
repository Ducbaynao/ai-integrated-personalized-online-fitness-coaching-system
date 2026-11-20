import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Modal, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { workoutScheduleQueryKeys } from '@/features/workout-schedule/workoutScheduleQueries';
import { formatScheduleTime } from '@/features/workout-schedule/scheduleDisplay';
import { aiRecommendationStatusLabels } from '@/localization/displayLabels';
import { aiWorkoutRescheduleApi, getWorkoutRescheduleFailureKind } from '@/services/aiWorkoutRescheduleApi';
import { createCommandKey } from '@/services/coachingApi';
import type { AcceptRescheduleApiRequest, AcceptRescheduleApiResponse } from '@/types/aiWorkoutReschedule';
import type { PlannedWorkoutOccurrence } from '@/types/workoutSchedule';
import { areSameInstants, readReschedulePayload } from './payloadBoundary';
import { isPeriodEffective, rescheduleQueryKeys, useCurrentStudentPeriod, useRescheduleDetail, type RescheduleActorCapability } from './rescheduleQueries';

interface Props {
  visible: boolean;
  actorId: string;
  capability: RescheduleActorCapability;
  studentId: string;
  occurrence: PlannedWorkoutOccurrence;
  authorized: boolean;
  initialRecommendationId?: string;
  onClose: () => void;
  onApplied: (result: AcceptRescheduleApiResponse) => void;
}

function failureMessage(error: unknown): string {
  switch (getWorkoutRescheduleFailureKind(error)) {
    case 'VALIDATION': return 'Thông tin yêu cầu chưa hợp lệ. Hãy kiểm tra lại.';
    case 'AUTHENTICATION': return 'Phiên đăng nhập đã hết hạn.';
    case 'AUTHORITY': return 'Quyền xem hoặc quyết định đã thay đổi.';
    case 'NOT_FOUND': return 'Đề xuất hoặc buổi tập không còn khả dụng.';
    case 'CONFLICT': return 'Lịch, phiên bản hoặc trạng thái đã đổi. Hãy tải lại và xem xét lại.';
    case 'TIMEOUT': return 'Yêu cầu quá thời gian chờ; chưa xác định được kết quả.';
    case 'NETWORK': return 'Mất kết nối; chưa xác định được kết quả.';
    case 'CANCELLED': return 'Yêu cầu đã bị hủy.';
    default: return 'Chưa thể xử lý đề xuất. Vui lòng thử lại sau.';
  }
}

const supervisionLabels = {
  SELF_PERFORMABLE: 'Có thể tự tập',
  COACH_OPTIONAL: 'Huấn luyện viên tùy chọn',
  COACH_REQUIRED: 'Bắt buộc có Huấn luyện viên',
} as const;

export function AiWorkoutRescheduleModal({ visible, actorId, capability, studentId, occurrence,
  authorized, initialRecommendationId, onClose, onApplied }: Props) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const client = useQueryClient();
  const occurrenceId = occurrence.occurrenceId;
  const [recommendationId, setRecommendationId] = useState<string | null>(initialRecommendationId ?? null);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [generationUnknown, setGenerationUnknown] = useState(false);
  const [acceptUnknown, setAcceptUnknown] = useState(false);
  const [conflictReview, setConflictReview] = useState(false);
  const [acceptIntent, setAcceptIntent] = useState<AcceptRescheduleApiRequest | null>(null);
  const [receipt, setReceipt] = useState<AcceptRescheduleApiResponse | null>(null);
  const [concealed, setConcealed] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const submitting = useRef(false);
  const period = useCurrentStudentPeriod(actorId, visible && authorized && capability === 'STUDENT' && !concealed);
  const detail = useRescheduleDetail(actorId, capability, studentId, occurrenceId, recommendationId,
    visible && authorized && !concealed);
  const isHumanCoach = capability === 'STUDENT' && Boolean(
    period.data &&
    period.data.mode === 'HUMAN_COACH' &&
    (!period.data.startedAt || isPeriodEffective(period.data, now))
  );
  const isSelfDirected = capability === 'STUDENT' && !isHumanCoach;
  const canManage = authorized && !concealed && (capability === 'TRAINER' ||
    (!period.isPending && !period.isError && isSelfDirected));

  const clearSensitive = useCallback(() => {
    setConcealed(true);
    setAcceptIntent(null);
    setReceipt(null);
    client.removeQueries({ queryKey: rescheduleQueryKeys.scope(actorId, capability, studentId) });
  }, [actorId, capability, client, studentId]);

  useEffect(() => {
    if (!visible || !authorized) {
      client.removeQueries({ queryKey: rescheduleQueryKeys.scope(actorId, capability, studentId) });
    }
  }, [visible, authorized, actorId, capability, studentId, client]);

  useEffect(() => {
    const error = detail.error ?? (capability === 'STUDENT' ? period.error : null);
    const kind = getWorkoutRescheduleFailureKind(error);
    if (visible && authorized && !concealed && (kind === 'AUTHENTICATION' || kind === 'AUTHORITY' || kind === 'NOT_FOUND')) {
      queueMicrotask(clearSensitive);
    }
  }, [visible, authorized, concealed, detail.error, period.error, capability, clearSensitive]);

  const close = () => {
    setRecommendationId(null); setError(''); setGenerationUnknown(false); setAcceptUnknown(false);
    setAcceptIntent(null); setReceipt(null); setConcealed(false); setReason(''); setConflictReview(false);
    client.removeQueries({ queryKey: rescheduleQueryKeys.scope(actorId, capability, studentId) });
    onClose();
  };

  const invalidateSchedule = async () => {
    await client.invalidateQueries({ queryKey: capability === 'STUDENT'
      ? workoutScheduleQueryKeys.student(studentId)
      : workoutScheduleQueryKeys.trainer(actorId, studentId) });
  };

  const generate = useMutation({
    retry: false,
    mutationFn: (commandKey: string) => aiWorkoutRescheduleApi.generate({
      studentId, occurrenceId, expectedOccurrenceVersion: occurrence.occurrenceVersion,
      commandKey, ...(reason.trim() ? { reason: reason.trim() } : {}),
    }),
    onSuccess: (result) => { setRecommendationId(result.recommendationId); setError(''); },
    onError: async (cause) => {
      const kind = getWorkoutRescheduleFailureKind(cause);
      if (kind === 'AUTHENTICATION' || kind === 'AUTHORITY' || kind === 'NOT_FOUND') clearSensitive();
      if (kind === 'TIMEOUT' || kind === 'NETWORK') setGenerationUnknown(true);
      if (kind === 'CONFLICT') { setConflictReview(true); await invalidateSchedule(); }
      setError(failureMessage(cause));
    },
    onSettled: () => { submitting.current = false; },
  });

  const accept = useMutation({
    retry: false,
    mutationFn: (body: AcceptRescheduleApiRequest) => aiWorkoutRescheduleApi.accept(recommendationId!, body),
    onSuccess: async (result) => {
      setReceipt(result); setAcceptIntent(null); setAcceptUnknown(false); setError('');
      await client.invalidateQueries({ queryKey: rescheduleQueryKeys.occurrence(actorId, capability, studentId, occurrenceId) });
      await invalidateSchedule();
      onApplied(result);
    },
    onError: async (cause) => {
      const kind = getWorkoutRescheduleFailureKind(cause);
      setError(failureMessage(cause));
      if (kind === 'AUTHENTICATION' || kind === 'AUTHORITY' || kind === 'NOT_FOUND') clearSensitive();
      else if (kind === 'CONFLICT') {
        setAcceptIntent(null); setAcceptUnknown(false); setConflictReview(true);
        await detail.refetch(); await invalidateSchedule();
      } else if (kind === 'TIMEOUT' || kind === 'NETWORK') {
        setAcceptUnknown(true);
        await detail.refetch(); // A read can establish state; it cannot recover a lost generation ID.
      } else setAcceptIntent(null);
    },
    onSettled: () => { submitting.current = false; },
  });

  const reject = useMutation({
    retry: false,
    mutationFn: () => aiWorkoutRescheduleApi.reject(recommendationId!),
    onSuccess: async () => {
      setError(''); setAcceptIntent(null);
      await client.invalidateQueries({ queryKey: rescheduleQueryKeys.occurrence(actorId, capability, studentId, occurrenceId) });
      await detail.refetch();
    },
    onError: async (cause) => {
      setError(failureMessage(cause));
      const kind = getWorkoutRescheduleFailureKind(cause);
      if (kind === 'AUTHENTICATION' || kind === 'AUTHORITY' || kind === 'NOT_FOUND') clearSensitive();
      else if (kind === 'TIMEOUT' || kind === 'NETWORK' || kind === 'CONFLICT') {
        setConflictReview(true);
        await detail.refetch();
      }
    },
  });

  const runAccept = (body: AcceptRescheduleApiRequest) => {
    if (submitting.current || !canManage || !recommendationId) return;
    const replayApplied = canRetryAccept && detail.data?.status === 'APPLIED' && body === acceptIntent;
    if (!replayApplied && (!detail.data?.expiresAt || Date.parse(detail.data.expiresAt) <= Date.now())) {
      setNow(Date.now()); setError('Đề xuất đã hết hạn. Hãy tải lại lịch trước khi tạo đề xuất mới.');
      return;
    }
    submitting.current = true; setAcceptIntent(body); setError(''); accept.mutate(body);
  };

  const payload = readReschedulePayload(detail.data?.structuredPayload, occurrenceId,
    occurrence.sessionSummary?.supervisionRequirement ?? null);
  const sameSourceStart = payload.supported && areSameInstants(payload.previousPlannedStartAt, occurrence.plannedStartAt);
  const matchesDetail = detail.data?.studentId === studentId && detail.data.targetEntityId === occurrenceId &&
    detail.data.recommendationType === 'WORKOUT_RESCHEDULE_PROPOSAL';
  const expired = detail.data?.expiresAt ? Date.parse(detail.data.expiresAt) <= now : true;
  const canDecide = canManage && matchesDetail && detail.data?.decisionAuthority === capability &&
    payload.supported && sameSourceStart && detail.data?.status === 'PENDING' && !expired && !conflictReview &&
    occurrence.status === 'SCHEDULED' && !detail.isFetching && !acceptUnknown;
  const canRetryAccept = acceptUnknown && canManage && matchesDetail && detail.data?.decisionAuthority === capability &&
    payload.supported && sameSourceStart && !conflictReview && !detail.isError && !detail.isFetching &&
    acceptIntent && (detail.data?.status === 'PENDING' || detail.data?.status === 'APPLIED');

  return <Modal visible={visible} animationType="slide" onRequestClose={close}>
    <ScrollView testID="f05-modal" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
      <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>AI đề xuất xếp lại buổi tập</Text>
      <WorkoutButton label="Đóng" secondary onPress={close} />
      {!authorized || concealed ? <WorkoutState testID="f05-concealed" title="Không còn quyền xem đề xuất"
        message="Dữ liệu đề xuất đã được ẩn. Hãy kiểm tra lại quyền và lịch tập." /> : <>
        <WorkoutCard>
          <Text style={[styles.body, { color: theme.textPrimary }]}>AI chỉ đề xuất đổi một Planned Workout. Lịch hẹn với Huấn luyện viên cần được xử lý riêng.</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi hiện tại: {formatScheduleTime(occurrence.plannedStartAt, occurrence.timezone)}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Yêu cầu giám sát: {occurrence.sessionSummary?.supervisionRequirement
            ? supervisionLabels[occurrence.sessionSummary.supervisionRequirement] : 'Chưa xác định'}</Text>
          {occurrence.linkedAppointmentId ? <Text style={[styles.body, { color: theme.warningText }]}>Buổi này có lịch hẹn liên kết. Chấp nhận đề xuất không tự đổi lịch hẹn; hãy hoàn tất workflow lịch hẹn khi cần.</Text> : null}
        </WorkoutCard>
        {capability === 'STUDENT' && period.isPending ? <WorkoutState busy title="Đang kiểm tra chế độ huấn luyện" message="Vui lòng chờ." /> : null}
        {capability === 'STUDENT' && period.isError ? <WorkoutState title="Chưa xác minh được quyền quyết định" message={failureMessage(period.error)} onRetry={() => void period.refetch()} /> : null}
        {capability === 'STUDENT' && !period.isPending && !period.isError && isHumanCoach ?
          <WorkoutState testID="f05-student-human-coach" title="Huấn luyện viên quyết định lịch tập"
            message="Học viên ở chế độ có Huấn luyện viên không thể tự áp dụng đề xuất đổi Planned Workout." /> : null}
        {!recommendationId && canManage && occurrence.status === 'SCHEDULED' ? <WorkoutCard>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do bỏ lỡ hoặc cần đổi lịch (tùy chọn)</Text>
          <TextInput accessibilityLabel="Lý do cần xếp lại buổi tập" value={reason} onChangeText={setReason}
            multiline maxLength={1000} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
          <WorkoutButton testID="f05-generate" label="Tạo đề xuất AI" busy={generate.isPending}
            disabled={generate.isPending || generationUnknown || conflictReview} onPress={() => {
              if (submitting.current) return;
              submitting.current = true; setError(''); generate.mutate(createCommandKey());
            }} />
        </WorkoutCard> : null}
        {generationUnknown ? <WorkoutState testID="f05-generation-unknown" title="Chưa rõ kết quả tạo đề xuất"
          message="Không có mã kết quả để tra cứu. Hệ thống sẽ không tự gửi lại yêu cầu này; hãy kiểm tra sau trước khi tạo yêu cầu mới." /> : null}
        {recommendationId && detail.isPending ? <WorkoutState busy title="Đang đọc đề xuất" message="Vui lòng chờ." /> : null}
        {recommendationId && detail.isError && !concealed ? <WorkoutState title="Chưa đọc được đề xuất" message={failureMessage(detail.error)}
          onRetry={() => void detail.refetch()} /> : null}
        {detail.data && !detail.isError && matchesDetail ? <WorkoutCard testID="f05-proposal">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{detail.data.title}</Text>
          <Text style={[styles.label, { color: theme.primary }]}>Đề xuất AI · {aiRecommendationStatusLabels[detail.data.status]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Người quyết định: {detail.data.decisionAuthority === 'TRAINER' ? 'Huấn luyện viên' : 'Học viên'}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Hết hạn: {detail.data.expiresAt ? formatScheduleTime(detail.data.expiresAt, occurrence.timezone) : 'Chưa xác định'}</Text>
          {!payload.supported ? <WorkoutState testID="f05-unsupported" title="Đề xuất chưa được hỗ trợ" message={payload.reason} /> : <>
            <Text style={[styles.body, { color: theme.textPrimary }]}>{payload.summary}</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>Từ: {formatScheduleTime(payload.previousPlannedStartAt, occurrence.timezone)}</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>Đến: {formatScheduleTime(payload.proposedStartAt, payload.timezone)} → {formatScheduleTime(payload.proposedEndAt, payload.timezone)}</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>Giám sát giữ nguyên: {supervisionLabels[payload.supervisionRequirement]}</Text>
            <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do và dữ liệu</Text>
            {payload.reasoning.map((line, index) => <Text key={`reason-${index}`} style={[styles.body, { color: theme.textSecondary }]}>• {line}</Text>)}
            {payload.usedData.map((line, index) => <Text key={`used-${index}`} style={[styles.body, { color: theme.textSecondary }]}>Dùng: {line}</Text>)}
            <Text style={[styles.label, { color: theme.textPrimary }]}>Dữ liệu thiếu</Text>
            {payload.missingData.length ? payload.missingData.map((line, index) => <Text key={`missing-${index}`} style={[styles.body, { color: theme.warningText }]}>• {line}</Text>)
              : <Text style={[styles.body, { color: theme.textSecondary }]}>Không được báo thiếu trong đề xuất.</Text>}
            {payload.limitations.map((line, index) => <Text key={`limit-${index}`} style={[styles.body, { color: theme.warningText }]}>Giới hạn: {line}</Text>)}
          </>}
          {expired && detail.data.status === 'PENDING' ? <WorkoutState title="Đề xuất đã hết hạn" message="Hãy tạo đề xuất mới sau khi xem lại lịch." /> : null}
          {payload.supported && !sameSourceStart ? <WorkoutState title="Buổi tập đã thay đổi"
            message="Mốc lịch gốc không còn khớp đề xuất. Hãy tải lại buổi tập trước khi quyết định." /> : null}
          {conflictReview ? <WorkoutState testID="f05-conflict-review" title="Lịch đã thay đổi"
            message="Hãy đóng đề xuất, tải lại buổi tập và tạo quyết định mới sau khi xem dữ liệu hiện tại." /> : null}
          {canDecide ? <View style={styles.row}>
            <WorkoutButton testID="f05-accept" label="Xác nhận đổi Planned Workout" busy={accept.isPending}
              disabled={accept.isPending || reject.isPending} onPress={() => runAccept({
                commandKey: createCommandKey(), expectedRevision: detail.data.revision,
                expectedOccurrenceVersion: occurrence.occurrenceVersion,
                ...(occurrence.linkedAppointmentId ? { expectedAppointmentId: occurrence.linkedAppointmentId } : {}),
                ...(occurrence.linkedAppointmentVersion != null ? { expectedAppointmentVersion: occurrence.linkedAppointmentVersion } : {}),
              })} />
            <WorkoutButton testID="f05-reject" label="Từ chối đề xuất" secondary busy={reject.isPending}
              disabled={accept.isPending || reject.isPending} onPress={() => reject.mutate()} />
          </View> : null}
        </WorkoutCard> : null}
        {detail.data && !matchesDetail && !detail.isError ? <WorkoutState testID="f05-detail-mismatch" title="Đề xuất không khớp buổi tập"
          message="Không hiển thị nội dung hoặc hành động của đề xuất này." /> : null}
        {acceptUnknown ? <WorkoutState testID="f05-accept-unknown" title="Chưa rõ kết quả xác nhận"
          message="Đã kiểm tra lại trạng thái đề xuất. Chỉ gửi lại đúng commandKey và body cũ sau khi xem kết quả đọc mới nhất."
          onRetry={() => void detail.refetch()} /> : null}
        {canRetryAccept ? <WorkoutButton testID="f05-retry-accept" label={detail.data?.status === 'APPLIED' ? 'Lấy lại biên nhận' : 'Thử lại cùng quyết định'}
          busy={accept.isPending} disabled={accept.isPending} onPress={() => runAccept(acceptIntent)} /> : null}
        {receipt ? <WorkoutState testID="f05-receipt" title="Đã áp dụng lịch tập"
          message={`Buổi tập đã được cập nhật${receipt.replayed ? ' (biên nhận gửi lại)' : ''}. Xem lịch mới để kiểm tra; lịch hẹn không tự thay đổi.`} /> : null}
        {error ? <WorkoutState testID="f05-error" title="Chưa thể xử lý đề xuất" message={error} /> : null}
      </>}
    </ScrollView>
  </Modal>;
}
