import React, { useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, useColorScheme, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import { workoutScheduleQueryKeys } from '@/features/workout-schedule/workoutScheduleQueries';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { ApiError } from '@/types/auth';
import type { DecideWorkoutScheduleProposalRequest, WorkoutScheduleDecision } from '@/types/workoutScheduleProposal';
import { formatProposalInstant, formatProposalLocal, proposalErrorMessage, proposalStatusLabels } from './proposalPresentation';
import { useWorkoutScheduleProposalDetail, workoutScheduleProposalQueryKeys } from './workoutScheduleProposalQueries';

type Command = { proposalId: string; body: DecideWorkoutScheduleProposalRequest };

export function StudentScheduleProposalDetailScreen({ proposalId }: { proposalId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const query = useWorkoutScheduleProposalDetail(studentId, proposalId, allowed);
  const proposal = query.data;
  const [confirmation, setConfirmation] = useState<WorkoutScheduleDecision | null>(null);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  const [retryCommand, setRetryCommand] = useState<Command | null>(null);
  const [refreshingConflict, setRefreshingConflict] = useState(false);
  const [permissionLost, setPermissionLost] = useState(false);
  const submitting = useRef(false);

  const refresh = async () => {
    await Promise.all([
      client.invalidateQueries({ queryKey: workoutScheduleProposalQueryKeys.student(studentId) }),
      client.invalidateQueries({ queryKey: workoutScheduleQueryKeys.student(studentId) }),
    ]);
  };

  const mutation = useMutation({
    mutationFn: (command: Command) => workoutScheduleProposalApi.decide(command.proposalId, command.body),
    onSuccess: async (result, command) => {
      setRetryCommand(null);
      setConfirmation(null);
      setDecisionError(null);
      client.setQueryData(workoutScheduleProposalQueryKeys.detail(studentId, command.proposalId), result.proposal);
      await refresh();
      AccessibilityInfo.announceForAccessibility(command.body.decision === 'ACCEPT'
        ? 'Đã chấp nhận toàn bộ đề xuất và tạo lịch tập.' : 'Đã từ chối đề xuất lịch tập.');
    },
    onError: async (error) => {
      const status = error instanceof ApiError ? error.status : undefined;
      setDecisionError(proposalErrorMessage(error));
      if (status === 409 || status === 403 || status === 404) {
        setRetryCommand(null);
        setConfirmation(null);
        if (status === 403 || status === 404) setPermissionLost(true);
        setRefreshingConflict(true);
        try { await refresh(); }
        finally { setRefreshingConflict(false); }
      } else if (status !== 0 && (status === undefined || status < 500)) {
        setRetryCommand(null);
      }
    },
    onSettled: () => { submitting.current = false; },
  });

  const openConfirmation = (decision: WorkoutScheduleDecision) => {
    if (proposal?.status !== 'PENDING' || submitting.current || query.isFetching || refreshingConflict || permissionLost) return;
    setConfirmation(decision);
    setRetryCommand(null);
    setDecisionError(null);
  };
  const sendDecision = () => {
    if (!proposal || proposal.status !== 'PENDING' || !confirmation || submitting.current || mutation.isPending
      || query.isFetching || query.isError || refreshingConflict || permissionLost) return;
    const command = retryCommand?.proposalId === proposalId && retryCommand.body.decision === confirmation
      ? retryCommand
      : { proposalId, body: { expectedProposalVersion: proposal.proposalVersion, decision: confirmation, commandKey: createCommandKey() } };
    submitting.current = true;
    setRetryCommand(command);
    setDecisionError(null);
    mutation.mutate(command);
  };

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem đề xuất." />;

  return <ScrollView testID="student-schedule-proposal-detail" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết đề xuất lịch</Text>
    <WorkoutButton label="Quay lại danh sách" secondary onPress={() => router.back()} />
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ trong giây lát." /> : null}
    {query.isError ? <WorkoutState testID="proposal-detail-error" title="Không thể xem đề xuất" message={proposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {permissionLost ? <WorkoutState testID="proposal-permission-lost" title="Không còn quyền xem đề xuất" message="Đề xuất không còn khả dụng. Hãy quay lại danh sách để tải dữ liệu mới." /> : null}
    {proposal && !query.isError && !permissionLost ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposalStatusLabels[proposal.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Huấn luyện viên đề xuất: {proposal.trainerDisplayName?.trim() || 'Chưa có tên huấn luyện viên'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch nguồn: {proposal.sourcePlanName?.trim() || 'Chưa có tên kế hoạch'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản nguồn: {proposal.sourcePlanVersionNumber}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Tuần bắt đầu: {proposal.weekAnchorDate} · Múi giờ: {proposal.timezone}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất lúc: {formatProposalInstant(proposal.proposedAt, user?.timezone || 'UTC')}</Text>
        {proposal.decidedAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Đã quyết định lúc: {formatProposalInstant(proposal.decidedAt, user?.timezone || 'UTC')}</Text> : null}
        {proposal.decisionNote ? <Text style={[styles.body, { color: theme.textSecondary }]}>Ghi chú quyết định: {proposal.decisionNote}</Text> : null}
        {proposal.status === 'WITHDRAWN' && proposal.withdrawnAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Đã rút lúc: {formatProposalInstant(proposal.withdrawnAt, user?.timezone || 'UTC')}</Text> : null}
      </WorkoutCard>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Các buổi trong đề xuất ({proposal.items.length})</Text>
      {proposal.items.map((item) => <WorkoutCard key={item.clientItemId} testID={`proposal-item-${item.clientItemId}`}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{item.sourceSessionName?.trim() || `Buổi ${item.sequenceNumber}`} · Tuần {item.weekNumber} · Ngày {item.dayNumber}</Text>
        {item.sourceSessionFocus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {item.sourceSessionFocus}</Text> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu: {formatProposalLocal(item.localStart, item.startUtcOffset, proposal.timezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc: {formatProposalLocal(item.localEnd, item.endUtcOffset, proposal.timezone)}</Text>
      </WorkoutCard>)}
      {proposal.status === 'PENDING' ? <WorkoutCard>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bạn chỉ có thể chấp nhận hoặc từ chối toàn bộ đề xuất. Lịch tập chỉ được tạo sau khi máy chủ xác nhận thành công.</Text>
        <View style={styles.row}>
          <WorkoutButton testID="proposal-accept" label="Chấp nhận đề xuất" disabled={mutation.isPending || query.isFetching || refreshingConflict} onPress={() => openConfirmation('ACCEPT')} />
          <WorkoutButton testID="proposal-reject" label="Từ chối đề xuất" secondary disabled={mutation.isPending || query.isFetching || refreshingConflict} onPress={() => openConfirmation('REJECT')} />
        </View>
      </WorkoutCard> : null}
      {proposal.status === 'ACCEPTED' ? <WorkoutButton testID="proposal-open-calendar" label="Xem lịch tập đã xác nhận" onPress={() => router.push('/workouts/schedule' as never)} /> : null}
      {confirmation && proposal.status === 'PENDING' ? <WorkoutCard testID="proposal-confirmation">
        <Text accessibilityRole="alert" style={[styles.h2, { color: theme.textPrimary }]}>{confirmation === 'ACCEPT' ? 'Xác nhận chấp nhận toàn bộ lịch?' : 'Xác nhận từ chối toàn bộ đề xuất?'}</Text>
        {confirmation === 'ACCEPT' ? <>
          <Text style={[styles.body, { color: theme.textPrimary }]}>Múi giờ huấn luyện viên đã chọn cho đề xuất: {proposal.timezone}. Đây không phải múi giờ hồ sơ của bạn.</Text>
          {proposal.items.map((item) => <Text key={item.clientItemId} style={[styles.body, { color: theme.textPrimary }]}>{item.sourceSessionName?.trim() || `Buổi ${item.sequenceNumber}`}: {formatProposalLocal(item.localStart, item.startUtcOffset, proposal.timezone)} → {formatProposalLocal(item.localEnd, item.endUtcOffset, proposal.timezone)}</Text>)}
        </> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>{confirmation === 'ACCEPT'
          ? `Nếu hợp lệ, cả ${proposal.items.length} buổi sẽ được tạo cùng lúc; nếu có xung đột, không buổi nào được tạo.`
          : 'Không buổi tập nào được tạo từ đề xuất này.'}</Text>
        <View style={styles.row}>
          <WorkoutButton label="Xem lại" secondary disabled={mutation.isPending} onPress={() => { setConfirmation(null); setRetryCommand(null); setDecisionError(null); }} />
          <WorkoutButton testID="proposal-confirm-decision" label={confirmation === 'ACCEPT' ? 'Xác nhận chấp nhận' : 'Xác nhận từ chối'}
            danger={confirmation === 'REJECT'} busy={mutation.isPending} disabled={mutation.isPending} onPress={sendDecision} />
        </View>
      </WorkoutCard> : null}
    </> : null}
    {decisionError ? <WorkoutState testID="proposal-decision-error" title="Chưa thể xử lý đề xuất" message={decisionError}
      onRetry={retryCommand && confirmation ? sendDecision : undefined} /> : null}
  </ScrollView>;
}
