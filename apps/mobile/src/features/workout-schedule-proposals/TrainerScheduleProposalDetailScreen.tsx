import React, { useEffect, useRef, useState } from 'react';
import { AccessibilityInfo, ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import { ApiError } from '@/types/auth';
import type { WithdrawWorkoutScheduleProposalRequest } from '@/types/workoutScheduleProposal';
import { formatProposalInstant, formatProposalLocal, proposalErrorMessage, trainerProposalStatusLabels } from './proposalPresentation';
import { useTrainerWorkoutScheduleProposalDetail, workoutScheduleProposalQueryKeys } from './workoutScheduleProposalQueries';

export function TrainerScheduleProposalDetailScreen({ relationshipId, proposalId }: { relationshipId: string; proposalId: string }) {
  const { activeCapability, user } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship;
  const studentId = relationship?.studentId ?? '';
  const trainerId = user?.id ?? '';
  const permission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const canView = activeCapability === 'TRAINER' && relationship?.trainerId === trainerId && relationship.status === 'ACTIVE'
    && permission?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(permission.accessLevel ?? '');
  const canWithdraw = canView && ['CONTRIBUTE', 'MANAGE'].includes(permission?.accessLevel ?? '');
  const [concealed, setConcealed] = useState(false);
  const query = useTrainerWorkoutScheduleProposalDetail(trainerId, studentId, proposalId, canView && !concealed);
  const proposal = query.data?.studentId === studentId && query.data.trainerId === trainerId ? query.data : null;
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryBody, setRetryBody] = useState<WithdrawWorkoutScheduleProposalRequest | null>(null);
  const submitting = useRef(false);
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);

  useEffect(() => {
    let active = true;
    if (denied) queueMicrotask(() => { if (active) setConcealed(true); });
    if (studentId && (!canView || denied || concealed)) client.removeQueries({ queryKey: workoutScheduleProposalQueryKeys.trainer(trainerId, studentId) });
    return () => { active = false; };
  }, [canView, client, concealed, denied, studentId, trainerId]);

  const refresh = async () => {
    await client.invalidateQueries({ queryKey: workoutScheduleProposalQueryKeys.trainer(trainerId, studentId) });
  };
  const mutation = useMutation({
    mutationFn: (body: WithdrawWorkoutScheduleProposalRequest) => workoutScheduleProposalApi.withdraw(proposalId, body),
    onSuccess: async (result) => {
      setConfirming(false); setRetryBody(null); setError(null);
      client.setQueryData(workoutScheduleProposalQueryKeys.trainerDetail(trainerId, studentId, proposalId), result.proposal);
      await refresh();
      AccessibilityInfo.announceForAccessibility('Đã rút đề xuất lịch tập.');
    },
    onError: async (cause) => {
      const status = cause instanceof ApiError ? cause.status : undefined;
      setError(proposalErrorMessage(cause));
      if (status === 409 || status === 403 || status === 404) {
        setRetryBody(null); setConfirming(false);
        if (status === 403 || status === 404) setConcealed(true);
        await refresh();
      } else if (status !== 0 && (status === undefined || status < 500)) setRetryBody(null);
    },
    onSettled: () => { submitting.current = false; },
  });
  const withdraw = () => {
    if (!proposal || proposal.status !== 'PENDING' || !canWithdraw || !confirming || submitting.current || mutation.isPending || query.isFetching) return;
    const body = retryBody ?? { expectedProposalVersion: proposal.proposalVersion, commandKey: createCommandKey() };
    submitting.current = true;
    setRetryBody(body); setError(null);
    mutation.mutate(body);
  };

  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên." />;
  if (relationshipQuery.isPending || sharingQuery.isPending) return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." />;
  if (!canView || relationshipQuery.isError || sharingQuery.isError) return <WorkoutState title="Không thể xem đề xuất" message="Quan hệ hoặc quyền xem kế hoạch không còn hiệu lực." />;

  return <ScrollView testID="trainer-schedule-proposal-detail" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết đề xuất lịch</Text>
    <WorkoutButton label="Quay lại danh sách" secondary onPress={() => router.back()} />
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ." /> : null}
    {query.isError || concealed ? <WorkoutState testID="trainer-proposal-detail-error" title="Không thể xem đề xuất" message={proposalErrorMessage(query.error)} onRetry={denied || concealed ? undefined : () => query.refetch()} /> : null}
    {proposal && !query.isError && !concealed ? <>
      <WorkoutCard>
        <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposal.sourcePlanName?.trim() || 'Chưa có tên kế hoạch'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{trainerProposalStatusLabels[proposal.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {proposal.sourcePlanVersionNumber} · {proposal.items.length} buổi · {proposal.timezone}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất lúc: {formatProposalInstant(proposal.proposedAt, user?.timezone || 'UTC')}</Text>
      </WorkoutCard>
      {proposal.items.map((item) => <WorkoutCard key={item.clientItemId}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{item.sourceSessionName?.trim() || `Buổi ${item.sequenceNumber}`} · Tuần {item.weekNumber} · Ngày {item.dayNumber}</Text>
        {item.sourceSessionFocus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {item.sourceSessionFocus}</Text> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>Bắt đầu: {formatProposalLocal(item.localStart, item.startUtcOffset, proposal.timezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc: {formatProposalLocal(item.localEnd, item.endUtcOffset, proposal.timezone)}</Text>
      </WorkoutCard>)}
      {proposal.status === 'PENDING' && canWithdraw ? <WorkoutCard>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Rút đề xuất đang chờ. Không có lịch tập nào được tạo từ thao tác này.</Text>
        {!confirming ? <WorkoutButton testID="trainer-proposal-withdraw" label="Rút đề xuất" danger onPress={() => { setConfirming(true); setError(null); }} /> : <>
          <Text accessibilityRole="alert" style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận rút toàn bộ đề xuất?</Text>
          <WorkoutButton label="Giữ đề xuất" secondary disabled={mutation.isPending} onPress={() => { setConfirming(false); setRetryBody(null); }} />
          <WorkoutButton testID="trainer-proposal-confirm-withdraw" label="Xác nhận rút" danger busy={mutation.isPending} disabled={mutation.isPending || query.isFetching} onPress={withdraw} />
        </>}
      </WorkoutCard> : null}
      {proposal.status === 'PENDING' && !canWithdraw ? <WorkoutState title="Chỉ được xem" message="Cần quyền Đóng góp hiện hành để rút đề xuất." /> : null}
    </> : null}
    {error && !concealed ? <WorkoutState testID="trainer-proposal-withdraw-error" title="Chưa thể rút đề xuất" message={error} onRetry={retryBody && confirming ? withdraw : undefined} /> : null}
  </ScrollView>;
}
