import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleChangeProposalApi } from '@/services/workoutScheduleChangeProposalApi';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { workoutScheduleQueryKeys } from '@/features/workout-schedule/workoutScheduleQueries';
import { ApiError } from '@/types/auth';
import type { PlannedWorkoutChangeResult } from '@/types/workoutSchedule';
import type { DecideWorkoutScheduleChangeProposalRequest, WorkoutScheduleChangeProposalDecision } from '@/types/workoutScheduleChangeProposal';
import { changeProposalErrorMessage, changeTypeLabels, formatChangeTime, studentChangeProposalStatusLabels } from './changeProposalPresentation';
import { useStudentWorkoutScheduleChangeProposalDetail, workoutScheduleChangeProposalQueryKeys } from './workoutScheduleChangeProposalQueries';

type Command = { studentId: string; proposalId: string; body: DecideWorkoutScheduleChangeProposalRequest };

export function StudentChangeProposalDetailScreen({ proposalId }: { proposalId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const [permissionLost, setPermissionLost] = useState(false);
  const query = useStudentWorkoutScheduleChangeProposalDetail(studentId, proposalId, allowed && !permissionLost);
  const proposal = query.data?.studentId === studentId ? query.data : null;
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const [confirmation, setConfirmation] = useState<WorkoutScheduleChangeProposalDecision | null>(null);
  const [decisionReason, setDecisionReason] = useState('');
  const [retryCommand, setRetryCommand] = useState<Command | null>(null);
  const [needsReview, setNeedsReview] = useState(false);
  const [error, setError] = useState('');
  const [appliedChange, setAppliedChange] = useState<PlannedWorkoutChangeResult | null>(null);
  const submitting = useRef(false);

  useEffect(() => {
    if (!allowed || denied) queueMicrotask(() => {
      client.removeQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.student(studentId) });
      if (denied) setPermissionLost(true);
    });
  }, [allowed, client, denied, studentId]);

  useEffect(() => () => {
    client.removeQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.student(studentId) });
  }, [client, studentId]);

  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.student(studentId) }),
      client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) }),
    ]);
  };
  const mutation = useMutation({
    mutationFn: (command: Command) => workoutScheduleChangeProposalApi.decide(command.proposalId, command.body),
    onSuccess: async (result, command) => {
      setRetryCommand(null); setConfirmation(null); setError(''); setNeedsReview(false);
      setAppliedChange(result.appliedChange);
      client.setQueryData(workoutScheduleChangeProposalQueryKeys.studentDetail(command.studentId, command.proposalId), result.proposal);
      await refresh();
      AccessibilityInfo.announceForAccessibility(command.body.decision === 'ACCEPT'
        ? 'Đã chấp nhận đề xuất. Lịch tập đã được cập nhật.' : 'Đã từ chối đề xuất; lịch tập không thay đổi.');
    },
    onError: async (cause) => {
      const status = cause instanceof ApiError ? cause.status : undefined;
      setError(changeProposalErrorMessage(cause));
      if (status === 403 || status === 404) {
        setRetryCommand(null); setConfirmation(null); setPermissionLost(true);
        client.removeQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.student(studentId) });
        client.removeQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) });
      } else if (status === 409) {
        setRetryCommand(null); setConfirmation(null); setNeedsReview(true);
        await refresh();
        await query.refetch();
      } else if (status !== 0 && (status === undefined || status < 500)) setRetryCommand(null);
    },
    onSettled: () => { submitting.current = false; },
  });

  const openConfirmation = (decision: WorkoutScheduleChangeProposalDecision) => {
    if (!proposal || proposal.status !== 'PENDING' || !allowed || permissionLost || needsReview
      || submitting.current || mutation.isPending || query.isFetching || query.isError) return;
    setConfirmation(decision); setRetryCommand(null); setError('');
  };
  const send = () => {
    if (!proposal || proposal.status !== 'PENDING' || !confirmation || !allowed || permissionLost || needsReview
      || submitting.current || mutation.isPending || query.isFetching || query.isError) return;
    const reason = confirmation === 'ACCEPT' && proposal.changeType === 'CANCEL' ? decisionReason.trim() : '';
    if (confirmation === 'ACCEPT' && proposal.changeType === 'CANCEL'
      && new Date(proposal.oldPlannedStartAt).getTime() <= Date.now() && !reason) {
      setError('Buổi tập đã qua giờ bắt đầu. Hãy nhập lý do của bạn trước khi chấp nhận hủy.');
      return;
    }
    const body: DecideWorkoutScheduleChangeProposalRequest = retryCommand?.studentId === studentId
      && retryCommand.proposalId === proposalId && retryCommand.body.decision === confirmation
      && retryCommand.body.decisionReason === (reason || null)
      ? retryCommand.body : {
        expectedProposalVersion: proposal.proposalVersion, decision: confirmation,
        decisionReason: reason || null, commandKey: createCommandKey(),
      };
    submitting.current = true; setRetryCommand({ studentId, proposalId, body }); setError('');
    mutation.mutate({ studentId, proposalId, body });
  };

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem đề xuất." />;
  if (permissionLost || denied) return <WorkoutState testID="student-change-detail-denied" title="Không thể xem đề xuất" message="Đề xuất không tồn tại hoặc bạn không còn quyền xem. Dữ liệu đã lưu được ẩn." />;

  return <ScrollView testID="student-change-proposal-detail" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết đề xuất thay đổi lịch</Text>
    <WorkoutButton label="Quay lại danh sách" secondary onPress={() => router.back()} />
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ." /> : null}
    {query.isError && !denied ? <WorkoutState testID="student-change-detail-error" title="Không thể tải đề xuất" message={changeProposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {proposal && !query.isError ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposal.sourceSessionName?.trim() || 'Buổi tập theo lịch'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{changeTypeLabels[proposal.changeType]} · {studentChangeProposalStatusLabels[proposal.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Huấn luyện viên: {proposal.trainerDisplayName?.trim() || 'Chưa có tên hiển thị'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch nguồn: {proposal.sourcePlanName?.trim() || 'Chưa có tên kế hoạch'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất lúc: {formatChangeTime(proposal.proposedAt, proposal.oldTimezone)}</Text>
        {proposal.reason ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do huấn luyện viên nêu: {proposal.reason}</Text> : null}
      </WorkoutCard>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Lịch hiện tại khi đề xuất</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Bắt đầu: {formatChangeTime(proposal.oldPlannedStartAt, proposal.oldTimezone)}</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Kết thúc: {proposal.oldPlannedEndAt ? formatChangeTime(proposal.oldPlannedEndAt, proposal.oldTimezone) : 'Chưa xác định trong bản ghi cũ'}</Text>
        {proposal.oldLocalStart && proposal.oldStartUtcOffset ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ địa phương đã lưu: {proposal.oldLocalStart.replace('T', ' ')} UTC{proposal.oldStartUtcOffset} · {proposal.oldTimezone || 'Múi giờ chưa xác định'}</Text> : null}
        <WorkoutButton testID="student-change-open-occurrence" label="Xem buổi tập gốc" secondary onPress={() => router.push(`/workouts/schedule/${proposal.occurrenceId}` as never)} />
      </WorkoutCard>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Nội dung đề xuất</Text>
        {proposal.changeType === 'RESCHEDULE' && proposal.destination ? <>
          <Text style={[styles.body, { color: theme.textPrimary }]}>Bắt đầu mới: {proposal.destination.localStart.replace('T', ' ')} UTC{proposal.destination.startUtcOffset}</Text>
          <Text style={[styles.body, { color: theme.textPrimary }]}>Kết thúc mới: {proposal.destination.localEnd.replace('T', ' ')} UTC{proposal.destination.endUtcOffset}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Múi giờ IANA: {proposal.destination.timezone}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Nếu chấp nhận, máy chủ sẽ kiểm tra lại lịch và tạo occurrence thay thế. Buổi cũ sẽ còn trong lịch sử.</Text>
        </> : proposal.changeType === 'CANCEL' ? <Text style={[styles.body, { color: theme.textPrimary }]}>Huấn luyện viên đề xuất hủy buổi này. Nếu chấp nhận, buổi gốc chuyển sang Đã hủy; không tạo buổi thay thế.</Text>
          : <Text style={[styles.body, { color: theme.warningText }]}>Thiếu thời gian đề xuất. Hãy tải lại trước khi quyết định.</Text>}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất PENDING chưa tự đổi lịch. ACCEPT sẽ được máy chủ kiểm tra lại quyền huấn luyện viên, phiên bản buổi tập và xung đột; REJECT giữ nguyên lịch.</Text>
      </WorkoutCard>
      {proposal.decidedAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Đã quyết định lúc: {formatChangeTime(proposal.decidedAt, proposal.oldTimezone)}</Text> : null}
      {proposal.withdrawnAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Đã rút lúc: {formatChangeTime(proposal.withdrawnAt, proposal.oldTimezone)}</Text> : null}
      {proposal.status === 'PENDING' && !needsReview ? <WorkoutCard>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bạn vẫn có thể từ chối khi quan hệ huấn luyện đã kết thúc. Chấp nhận chỉ thành công nếu quyền và buổi tập còn hợp lệ lúc quyết định.</Text>
        <View style={styles.row}>
          {proposal.changeType !== 'RESCHEDULE' || proposal.destination ? <WorkoutButton testID="student-change-accept" label="Chấp nhận đề xuất" disabled={mutation.isPending || query.isFetching} onPress={() => openConfirmation('ACCEPT')} /> : null}
          <WorkoutButton testID="student-change-reject" label="Từ chối đề xuất" secondary disabled={mutation.isPending || query.isFetching} onPress={() => openConfirmation('REJECT')} />
        </View>
      </WorkoutCard> : null}
      {confirmation && proposal.status === 'PENDING' && !needsReview ? <WorkoutCard testID="student-change-confirmation">
        <Text accessibilityRole="alert" style={[styles.h2, { color: theme.textPrimary }]}>{confirmation === 'ACCEPT' ? 'Xác nhận chấp nhận thay đổi buổi tập?' : 'Xác nhận từ chối đề xuất?'}</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Buổi hiện tại: {formatChangeTime(proposal.oldPlannedStartAt, proposal.oldTimezone)}</Text>
        {confirmation === 'ACCEPT' && proposal.changeType === 'RESCHEDULE' && proposal.destination ? <Text style={[styles.body, { color: theme.textPrimary }]}>Giờ mới: {proposal.destination.localStart.replace('T', ' ')} UTC{proposal.destination.startUtcOffset} → {proposal.destination.localEnd.replace('T', ' ')} UTC{proposal.destination.endUtcOffset} · {proposal.destination.timezone}</Text> : null}
        {confirmation === 'ACCEPT' && proposal.changeType === 'CANCEL' ? <>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Bạn sẽ hủy buổi gốc. Nếu giờ bắt đầu đã qua theo máy chủ, lý do của bạn là bắt buộc; lý do của huấn luyện viên không thay thế lý do này.</Text>
          <TextInput testID="student-change-decision-reason" accessibilityLabel="Lý do của học viên khi chấp nhận hủy" placeholder="Nhập lý do nếu buổi đã qua giờ bắt đầu" multiline maxLength={1000}
            value={decisionReason} onChangeText={(value) => { setDecisionReason(value); setRetryCommand(null); setError(''); }} style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]} />
        </> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>{confirmation === 'ACCEPT' ? 'Máy chủ áp dụng thay đổi nguyên tử hoặc không thay đổi gì nếu có xung đột.' : 'Từ chối không thay đổi buổi tập.'}</Text>
        <View style={styles.row}>
          <WorkoutButton label="Xem lại" secondary disabled={mutation.isPending} onPress={() => { setConfirmation(null); setRetryCommand(null); setError(''); }} />
          <WorkoutButton testID="student-change-confirm-decision" label={confirmation === 'ACCEPT' ? 'Xác nhận chấp nhận' : 'Xác nhận từ chối'} danger={confirmation === 'REJECT'} busy={mutation.isPending} disabled={mutation.isPending} onPress={send} />
        </View>
      </WorkoutCard> : null}
      {appliedChange?.replacementOccurrenceId ? <WorkoutButton testID="student-change-open-replacement" label="Xem buổi tập thay thế" onPress={() => router.push(`/workouts/schedule/${appliedChange.replacementOccurrenceId}` as never)} /> : null}
      {proposal.status === 'ACCEPTED' && !appliedChange?.replacementOccurrenceId ? <WorkoutButton label="Xem lịch tập" onPress={() => router.push('/workouts/schedule' as never)} /> : null}
    </> : null}
    {needsReview ? <WorkoutState testID="student-change-review" title="Đề xuất hoặc lịch đã thay đổi" message="Dữ liệu mới đang được tải. Hãy xem lại rồi tự chọn chấp nhận hoặc từ chối bằng lệnh mới; hệ thống không tự chấp nhận lại." onRetry={() => { setNeedsReview(false); setError(''); }} /> : null}
    {error && !permissionLost ? <WorkoutState testID="student-change-decision-error" title="Chưa thể xử lý đề xuất" message={error} onRetry={retryCommand && confirmation && !needsReview ? send : undefined} /> : null}
  </ScrollView>;
}
