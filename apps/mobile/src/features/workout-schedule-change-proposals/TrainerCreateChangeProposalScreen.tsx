import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleChangeProposalApi } from '@/services/workoutScheduleChangeProposalApi';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { ApiError } from '@/types/auth';
import type { WorkoutScheduleChangeDestination } from '@/types/workoutSchedule';
import type { CreateWorkoutScheduleChangeProposalRequest, WorkoutScheduleChangeType } from '@/types/workoutScheduleChangeProposal';
import { useTrainerWorkoutScheduleDetail, workoutScheduleQueryKeys } from '@/features/workout-schedule/workoutScheduleQueries';
import { formatTrainerScheduleTime, plannedWorkoutStatusLabels } from '@/features/workout-schedule/scheduleDisplay';
import { validateScheduleDestination } from '@/features/workout-schedule/scheduleChange';
import { proposalTimezones, validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { changeProposalErrorMessage, changeTypeLabels } from './changeProposalPresentation';
import { workoutScheduleChangeProposalQueryKeys } from './workoutScheduleChangeProposalQueries';
import { useTrainerChangeProposalAuthority } from './useTrainerChangeProposalAuthority';

const EMPTY_DESTINATION: WorkoutScheduleChangeDestination = {
  timezone: '', localStart: '', localEnd: '', startUtcOffset: '', endUtcOffset: '',
};
type Command = { studentId: string; body: CreateWorkoutScheduleChangeProposalRequest };

export function TrainerCreateChangeProposalScreen({ relationshipId, occurrenceId }: { relationshipId: string; occurrenceId: string }) {
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const authority = useTrainerChangeProposalAuthority(relationshipId);
  const query = useTrainerWorkoutScheduleDetail(authority.trainerId, authority.studentId, occurrenceId, authority.canContribute);
  const item = query.data?.studentId === authority.studentId ? query.data : null;
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const [changeType, setChangeType] = useState<WorkoutScheduleChangeType | null>(null);
  const [destination, setDestination] = useState<WorkoutScheduleChangeDestination>(EMPTY_DESTINATION);
  const [timezoneSearch, setTimezoneSearch] = useState('');
  const [reason, setReason] = useState('');
  const [preview, setPreview] = useState(false);
  const [pending, setPending] = useState<Command | null>(null);
  const [needsReview, setNeedsReview] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);

  useEffect(() => {
    if (denied) queueMicrotask(authority.deny);
  }, [authority.deny, denied]);

  const resetReview = () => { setPreview(false); setPending(null); setError(''); };
  const editDestination = (patch: Partial<WorkoutScheduleChangeDestination>) => {
    setDestination((current) => ({ ...current, ...patch })); resetReview();
  };
  const rescheduleSourceReady = Boolean(item?.planSummary?.planVersionId && item.sessionSummary?.planSessionId
    && item.localStart && item.localEnd && item.timezone && item.startUtcOffset && item.endUtcOffset
    && item.sessionSummary?.supervisionRequirement
    && (item.sessionSummary.supervisionRequirement !== 'COACH_REQUIRED' || item.linkedAppointmentId));
  const canSelect = Boolean(item?.status === 'SCHEDULED' && authority.canContribute);
  const validate = () => {
    if (!canSelect || !item || !changeType) return 'Buổi tập hoặc quyền tạo đề xuất không còn phù hợp. Hãy tải lại lịch.';
    if (new Date(item.plannedStartAt).getTime() <= Date.now()) return 'Giờ bắt đầu hiện tại đã qua. Không thể tạo đề xuất thay đổi buổi này.';
    if (changeType === 'RESCHEDULE') {
      if (!rescheduleSourceReady) return 'Không đủ nguồn phiên bản, giờ địa phương hoặc metadata giám sát để đề xuất đổi lịch.';
      return validateScheduleDestination(destination);
    }
    return null;
  };
  const review = () => { const issue = validate(); setError(issue ?? ''); setPreview(!issue); setPending(null); };

  const mutation = useMutation({
    mutationFn: (command: Command) => workoutScheduleChangeProposalApi.create(command.studentId, command.body),
    onSuccess: async (result) => {
      setPending(null); setPreview(false); setError('');
      await client.invalidateQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.trainer(authority.trainerId, authority.studentId) });
      AccessibilityInfo.announceForAccessibility('Đã gửi đề xuất. Học viên sẽ quyết định, lịch chưa thay đổi.');
      router.replace(`/coaching/${relationshipId}/change-proposals/${result.proposal.proposalId}` as never);
    },
    onError: async (cause) => {
      const status = cause instanceof ApiError ? cause.status : undefined;
      setError(changeProposalErrorMessage(cause));
      if (status === 403 || status === 404) { setPending(null); setPreview(false); authority.deny(); }
      else if (status === 409) {
        setPending(null); setPreview(false); setNeedsReview(true);
        await client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.trainer(authority.trainerId, authority.studentId) });
        await query.refetch();
      } else if (status !== 0 && (status === undefined || status < 500)) setPending(null);
    },
    onSettled: () => { submitting.current = false; },
  });
  const send = () => {
    if (!item || !changeType || !preview || needsReview || submitting.current || mutation.isPending || query.isFetching || !authority.canContribute) return;
    const issue = validate();
    if (issue) { setError(issue); setPreview(false); setPending(null); return; }
    const command = pending ?? { studentId: authority.studentId, body: {
      occurrenceId: item.occurrenceId, expectedOccurrenceVersion: item.occurrenceVersion,
      changeType, ...(changeType === 'RESCHEDULE' ? { destination } : {}),
      reason: reason.trim() || null, commandKey: createCommandKey(),
    } };
    submitting.current = true; setPending(command); setError(''); mutation.mutate(command);
  };

  if (authority.activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên." />;
  if (authority.relationshipQuery.isPending || authority.sharingQuery.isPending || authority.verifying)
    return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." />;
  if (!authority.canContribute || denied || authority.relationshipQuery.isError || authority.sharingQuery.isError)
    return <WorkoutState testID="create-change-denied" title="Không thể tạo đề xuất" message="Cần quan hệ hiện hành và quyền Đóng góp kế hoạch. Dữ liệu lịch đã lưu không được hiển thị." onRetry={authority.retry} />;

  return <ScrollView testID="trainer-create-change-proposal" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Đề xuất thay đổi một buổi tập</Text>
    <WorkoutButton label="Quay lại lịch tập" secondary onPress={() => router.back()} />
    {query.isPending ? <WorkoutState busy title="Đang tải buổi tập" message="Vui lòng chờ." /> : null}
    {query.isError && !denied ? <WorkoutState testID="create-change-error" title="Không thể tải buổi tập" message={changeProposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {item && !query.isError ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{item.sessionSummary?.name?.trim() || 'Buổi tập theo lịch'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{plannedWorkoutStatusLabels[item.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {item.planSummary?.planName?.trim() || 'Chưa có tên kế hoạch'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu hiện tại: {formatTrainerScheduleTime(item.plannedStartAt, item.timezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc hiện tại: {item.plannedEndAt ? formatTrainerScheduleTime(item.plannedEndAt, item.timezone) : 'Chưa rõ trong bản ghi cũ'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Máy chủ sẽ kiểm tra lại occurrence version, execution seal, Appointment và quyền trước khi lưu đề xuất. Tạo đề xuất chưa đổi lịch.</Text>
      </WorkoutCard>
      {!canSelect ? <WorkoutState testID="create-change-ineligible" title="Không thể đề xuất từ buổi này" message="Cần occurrence SCHEDULED và quyền Đóng góp hiện hành." /> : <>
        <View style={styles.row}>
          <WorkoutButton testID="select-change-reschedule" label="Đề xuất đổi lịch" secondary={changeType !== 'RESCHEDULE'} disabled={!rescheduleSourceReady}
            onPress={() => { setChangeType('RESCHEDULE'); resetReview(); }} />
          <WorkoutButton testID="select-change-cancel" label="Đề xuất hủy" secondary={changeType !== 'CANCEL'} onPress={() => { setChangeType('CANCEL'); resetReview(); }} />
        </View>
        {!rescheduleSourceReady ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>
          {item.sessionSummary?.supervisionRequirement === 'COACH_REQUIRED'
            ? 'Buổi cần Huấn luyện viên giám sát nhưng không có liên kết lịch hẹn hiện hành; không thể đề xuất đổi lịch.'
            : 'Thiếu metadata nguồn, local-time intent hoặc supervision; không thể đề xuất đổi lịch từ bản ghi này.'}
        </Text> : null}
        {changeType === 'RESCHEDULE' && rescheduleSourceReady ? <WorkoutCard testID="create-change-reschedule-form">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Giờ thay thế</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn tường minh IANA timezone, giờ bắt đầu/kết thúc địa phương và UTC offset. Không suy thời lượng từ kế hoạch.</Text>
          {item.linkedAppointmentId ? <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi đề xuất phải nằm trọn trong cùng lịch hẹn CONFIRMED. Lịch hẹn không tự đổi; backend sẽ kiểm lại ID và phiên bản khi Học viên chấp nhận.</Text> : null}
          {item.timezone ? <WorkoutButton label={`Dùng múi giờ occurrence: ${item.timezone}`} secondary onPress={() => editDestination({ timezone: item.timezone ?? '', startUtcOffset: '', endUtcOffset: '' })} /> : null}
          <TextInput testID="change-timezone-search" accessibilityLabel="Tìm múi giờ IANA" placeholder="Tìm, ví dụ Asia/Ho_Chi_Minh" value={timezoneSearch} onChangeText={setTimezoneSearch}
            style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
          <Text style={[styles.label, { color: theme.textPrimary }]}>Múi giờ đã chọn: {destination.timezone || 'Chưa chọn'}</Text>
          {proposalTimezones(timezoneSearch).map((zone) => <WorkoutButton key={zone} label={zone} secondary={zone !== destination.timezone}
            onPress={() => editDestination({ timezone: zone, startUtcOffset: '', endUtcOffset: '' })} />)}
          {(['Start', 'End'] as const).map((part) => {
            const local = part === 'Start' ? destination.localStart : destination.localEnd;
            const offset = part === 'Start' ? destination.startUtcOffset : destination.endUtcOffset;
            const choices = validProposalOffsets(destination.timezone, local);
            return <View key={part} style={{ gap: spacing.xs }}>
              <Text style={[styles.label, { color: theme.textPrimary }]}>{part === 'Start' ? 'Bắt đầu mới' : 'Kết thúc mới'}</Text>
              <TextInput testID={`change-local-${part.toLowerCase()}`} accessibilityLabel={`${part === 'Start' ? 'Bắt đầu' : 'Kết thúc'} địa phương`}
                placeholder="YYYY-MM-DDTHH:mm" value={local} onChangeText={(value) => editDestination(part === 'Start'
                  ? { localStart: value, startUtcOffset: '' } : { localEnd: value, endUtcOffset: '' })}
                style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
              {local && !choices.length ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Giờ không tồn tại trong múi giờ này hoặc sai định dạng.</Text> : null}
              {choices.length > 1 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ lặp khi đổi DST. Chọn một UTC offset rõ ràng.</Text> : null}
              <View style={styles.row}>{choices.map((choice) => <WorkoutButton key={choice.offset} label={`UTC${choice.offset}`} secondary={offset !== choice.offset}
                onPress={() => editDestination(part === 'Start' ? { startUtcOffset: choice.offset } : { endUtcOffset: choice.offset })} />)}</View>
            </View>;
          })}
        </WorkoutCard> : null}
        {changeType ? <WorkoutCard>
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Lý do đề xuất</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Có thể nêu lý do để Học viên hiểu trước khi quyết định. Hủy theo đề xuất vẫn cần Học viên ACCEPT; nếu lúc đó đã qua giờ bắt đầu, backend yêu cầu Học viên cung cấp lý do quyết định.</Text>
          <TextInput testID="change-proposal-reason" accessibilityLabel="Lý do đề xuất thay đổi lịch" placeholder="Lý do (không bắt buộc)" multiline maxLength={1000} value={reason}
            onChangeText={(value) => { setReason(value); resetReview(); }} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
          <WorkoutButton testID="review-change-proposal" label="Xem trước đề xuất" disabled={mutation.isPending} onPress={review} />
        </WorkoutCard> : null}
        {preview && changeType ? <WorkoutCard testID="change-proposal-preview">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận gửi {changeTypeLabels[changeType].toLowerCase()}</Text>
          <Text style={[styles.body, { color: theme.textPrimary }]}>Buổi hiện tại: {item.sessionSummary?.name?.trim() || 'Buổi tập theo lịch'} · {formatTrainerScheduleTime(item.plannedStartAt, item.timezone)}</Text>
          {changeType === 'RESCHEDULE' ? <Text style={[styles.body, { color: theme.textPrimary }]}>Giờ đề xuất: {destination.localStart} UTC{destination.startUtcOffset} → {destination.localEnd} UTC{destination.endUtcOffset} · {destination.timezone}</Text> : null}
          {item.linkedAppointmentId ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lịch hẹn hiện có là một lifecycle riêng. Đề xuất hủy buổi tập không hủy lịch hẹn; đề xuất đổi lịch không tự dời lịch hẹn.</Text> : null}
          {reason.trim() ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do: {reason.trim()}</Text> : null}
          <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ một occurrence. Học viên sẽ ACCEPT/REJECT; gửi đề xuất PENDING không đổi occurrence hay giữ chỗ trên lịch.</Text>
          <WorkoutButton testID="confirm-create-change-proposal" label="Gửi đề xuất cho Học viên" busy={mutation.isPending} disabled={mutation.isPending || needsReview} onPress={send} />
        </WorkoutCard> : null}
      </>}
      {needsReview ? <WorkoutState testID="create-change-review" title="Dữ liệu đã thay đổi" message="Occurrence đã được tải lại. Hãy kiểm tra, xem trước và xác nhận một lệnh mới; lệnh cũ sẽ không được phát lại." onRetry={() => { setNeedsReview(false); resetReview(); }} /> : null}
      {error ? <WorkoutState testID="create-change-action-error" title="Chưa thể tạo đề xuất" message={error} onRetry={pending && !needsReview ? send : undefined} /> : null}
    </> : null}
  </ScrollView>;
}
