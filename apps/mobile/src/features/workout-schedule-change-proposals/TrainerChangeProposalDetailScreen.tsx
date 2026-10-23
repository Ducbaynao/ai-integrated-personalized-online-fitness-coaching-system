import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleChangeProposalApi } from '@/services/workoutScheduleChangeProposalApi';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { ApiError } from '@/types/auth';
import type { WithdrawWorkoutScheduleChangeProposalRequest } from '@/types/workoutScheduleChangeProposal';
import { changeProposalErrorMessage, changeProposalStatusLabels, changeTypeLabels, formatChangeTime } from './changeProposalPresentation';
import { useTrainerWorkoutScheduleChangeProposalDetail, workoutScheduleChangeProposalQueryKeys } from './workoutScheduleChangeProposalQueries';
import { useTrainerChangeProposalAuthority } from './useTrainerChangeProposalAuthority';

export function TrainerChangeProposalDetailScreen({ relationshipId, proposalId }: { relationshipId: string; proposalId: string }) {
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const authority = useTrainerChangeProposalAuthority(relationshipId);
  const query = useTrainerWorkoutScheduleChangeProposalDetail(
    authority.trainerId, authority.studentId, proposalId, authority.canView,
  );
  const proposal = query.data?.studentId === authority.studentId && query.data.trainerId === authority.trainerId ? query.data : null;
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const [confirming, setConfirming] = useState(false);
  const [retryBody, setRetryBody] = useState<WithdrawWorkoutScheduleChangeProposalRequest | null>(null);
  const [needsReview, setNeedsReview] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);

  useEffect(() => {
    if (denied) queueMicrotask(authority.deny);
  }, [authority.deny, denied]);

  const mutation = useMutation({
    mutationFn: (body: WithdrawWorkoutScheduleChangeProposalRequest) => workoutScheduleChangeProposalApi.withdraw(proposalId, body),
    onSuccess: async (result) => {
      setConfirming(false); setRetryBody(null); setNeedsReview(false); setError('');
      client.setQueryData(workoutScheduleChangeProposalQueryKeys.detail(authority.trainerId, authority.studentId, proposalId), result.proposal);
      await client.invalidateQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.trainer(authority.trainerId, authority.studentId) });
      AccessibilityInfo.announceForAccessibility('Đã rút đề xuất đổi hoặc hủy lịch.');
    },
    onError: async (cause) => {
      const status = cause instanceof ApiError ? cause.status : undefined;
      setError(changeProposalErrorMessage(cause));
      if (status === 403 || status === 404) { setRetryBody(null); setConfirming(false); authority.deny(); }
      else if (status === 409) {
        setRetryBody(null); setConfirming(false); setNeedsReview(true);
        await client.invalidateQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.trainer(authority.trainerId, authority.studentId) });
        await query.refetch();
      } else if (status !== 0 && (status === undefined || status < 500)) setRetryBody(null);
    },
    onSettled: () => { submitting.current = false; },
  });
  const withdraw = () => {
    if (!proposal || proposal.status !== 'PENDING' || !authority.canContribute || !confirming
      || needsReview || query.isFetching || submitting.current || mutation.isPending) return;
    const body = retryBody ?? { expectedProposalVersion: proposal.proposalVersion, commandKey: createCommandKey() };
    submitting.current = true; setRetryBody(body); setError(''); mutation.mutate(body);
  };

  if (authority.activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên." />;
  if (authority.relationshipQuery.isPending || authority.sharingQuery.isPending || authority.verifying)
    return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." />;
  if (!authority.canView || denied || authority.relationshipQuery.isError || authority.sharingQuery.isError)
    return <WorkoutState testID="change-proposal-detail-denied" title="Không thể xem đề xuất" message="Quan hệ hoặc quyền xem kế hoạch không còn hiệu lực." onRetry={authority.retry} />;

  return <ScrollView testID="trainer-change-proposal-detail" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết đề xuất thay đổi lịch</Text>
    <WorkoutButton label="Quay lại danh sách" secondary onPress={() => router.back()} />
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ." /> : null}
    {query.isError && !denied ? <WorkoutState testID="change-proposal-detail-error" title="Không thể tải đề xuất" message={changeProposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {proposal && !query.isError ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposal.sourceSessionName?.trim() || 'Buổi tập theo lịch'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{changeTypeLabels[proposal.changeType]} · {changeProposalStatusLabels[proposal.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch nguồn: {proposal.sourcePlanName?.trim() || 'Chưa có tên kế hoạch'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ cũ: {formatChangeTime(proposal.oldPlannedStartAt, proposal.oldTimezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc cũ: {proposal.oldPlannedEndAt ? formatChangeTime(proposal.oldPlannedEndAt, proposal.oldTimezone) : 'Chưa rõ trong bản ghi cũ'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất lúc: {formatChangeTime(proposal.proposedAt, proposal.oldTimezone)}</Text>
        {proposal.reason ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do Trainer nêu: {proposal.reason}</Text> : null}
        {proposal.appointmentId ? <>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất gắn với một lịch hẹn đã xác nhận. Khi Học viên chấp nhận, máy chủ kiểm tra lại đúng lịch hẹn và phiên bản đã ghi nhận.</Text>
          <WorkoutButton testID="trainer-change-open-appointment" label="Xem lịch hẹn liên kết" secondary onPress={() => router.push(`/appointments/${proposal.appointmentId}` as never)} />
          <Text style={[styles.body, { color: theme.textSecondary }]}>Đổi lịch phải nằm trọn trong cùng lịch hẹn; hủy buổi tập không hủy lịch hẹn.</Text>
        </> : null}
        {proposal.decidedAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Học viên quyết định lúc: {formatChangeTime(proposal.decidedAt, proposal.oldTimezone)}</Text> : null}
        {proposal.withdrawnAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Đã rút lúc: {formatChangeTime(proposal.withdrawnAt, proposal.oldTimezone)}</Text> : null}
      </WorkoutCard>
      {proposal.changeType === 'RESCHEDULE' && proposal.destination ? <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Giờ mới được đề xuất</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Bắt đầu: {proposal.destination.localStart} UTC{proposal.destination.startUtcOffset}</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Kết thúc: {proposal.destination.localEnd} UTC{proposal.destination.endUtcOffset}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Múi giờ IANA: {proposal.destination.timezone}</Text>
      </WorkoutCard> : null}
      {proposal.status === 'PENDING' ? <WorkoutCard>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất chưa đổi occurrence. Chỉ Học viên có thể chấp nhận hoặc từ chối.</Text>
        {authority.canContribute && !needsReview ? (!confirming
          ? <WorkoutButton testID="change-proposal-withdraw" label="Rút đề xuất đang chờ" danger onPress={() => { setConfirming(true); setError(''); }} />
          : <>
            <Text accessibilityRole="alert" style={[styles.label, { color: theme.textPrimary }]}>Xác nhận rút đề xuất này? Lịch tập không thay đổi.</Text>
            <WorkoutButton label="Giữ đề xuất" secondary disabled={mutation.isPending} onPress={() => { setConfirming(false); setRetryBody(null); }} />
            <WorkoutButton testID="change-proposal-confirm-withdraw" label="Xác nhận rút" danger busy={mutation.isPending} disabled={mutation.isPending || query.isFetching} onPress={withdraw} />
          </>) : <WorkoutState title="Chỉ được xem" message="Cần quyền Đóng góp hiện hành để rút đề xuất." />}
      </WorkoutCard> : null}
      {needsReview ? <WorkoutState testID="change-proposal-review" title="Đề xuất đã thay đổi" message="Đã tải lại dữ liệu. Hãy kiểm tra trạng thái và quyết định rút lại bằng lệnh mới." onRetry={() => { setNeedsReview(false); setError(''); }} /> : null}
    </> : null}
    {error && authority.canView ? <WorkoutState testID="change-proposal-action-error" title="Chưa thể rút đề xuất" message={error} onRetry={retryBody && confirming && !needsReview ? withdraw : undefined} /> : null}
  </ScrollView>;
}
