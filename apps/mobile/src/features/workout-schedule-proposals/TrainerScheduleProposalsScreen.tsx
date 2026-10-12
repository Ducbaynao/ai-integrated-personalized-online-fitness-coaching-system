import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { ApiError } from '@/types/auth';
import type { WorkoutScheduleProposalStatus } from '@/types/workoutScheduleProposal';
import { formatProposalLocal, proposalErrorMessage, trainerProposalStatusLabels } from './proposalPresentation';
import { useTrainerWorkoutScheduleProposals, workoutScheduleProposalQueryKeys } from './workoutScheduleProposalQueries';

const filters: { label: string; status?: WorkoutScheduleProposalStatus }[] = [
  { label: 'Tất cả' }, { label: 'Đang chờ', status: 'PENDING' }, { label: 'Đã chấp nhận', status: 'ACCEPTED' },
  { label: 'Đã từ chối', status: 'REJECTED' }, { label: 'Đã rút', status: 'WITHDRAWN' },
];

export function TrainerScheduleProposalsScreen({ relationshipId }: { relationshipId: string }) {
  const { activeCapability, user } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [status, setStatus] = useState<WorkoutScheduleProposalStatus | undefined>();
  const [permissionLost, setPermissionLost] = useState(false);
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const relationship = relationshipQuery.data?.relationship;
  const studentId = relationship?.studentId ?? '';
  const trainerId = user?.id ?? '';
  const permission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const canView = activeCapability === 'TRAINER' && relationship?.trainerId === trainerId && relationship.status === 'ACTIVE'
    && permission?.state === 'ALLOWED' && ['VIEW', 'CONTRIBUTE', 'MANAGE'].includes(permission.accessLevel ?? '');
  const canCreate = canView && ['CONTRIBUTE', 'MANAGE'].includes(permission?.accessLevel ?? '');
  const query = useTrainerWorkoutScheduleProposals(trainerId, studentId, status, canView && !permissionLost);
  const items = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);

  useEffect(() => {
    let active = true;
    if (denied) queueMicrotask(() => { if (active) setPermissionLost(true); });
    if (studentId && (!canView || denied)) client.removeQueries({ queryKey: workoutScheduleProposalQueryKeys.trainer(trainerId, studentId) });
    return () => { active = false; };
  }, [canView, client, denied, studentId, trainerId]);

  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên." />;
  if (relationshipQuery.isPending || sharingQuery.isPending) return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." />;
  if (relationshipQuery.isError || sharingQuery.isError || !canView || permissionLost || denied) return <WorkoutState title="Không thể xem đề xuất" message="Quan hệ hoặc quyền xem kế hoạch không còn hiệu lực." onRetry={() => { relationshipQuery.refetch(); sharingQuery.refetch(); }} />;

  return <ScrollView testID="trainer-schedule-proposals" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Đề xuất lịch tập</Text>
    <WorkoutButton label="Quay lại học viên" secondary onPress={() => router.back()} />
    {canCreate ? <WorkoutButton testID="open-create-proposal" label="Tạo đề xuất lịch tập" onPress={() => router.push(`/coaching/${relationshipId}/schedule-proposals/new` as never)} /> : null}
    <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ hiển thị đề xuất bạn đã tạo cho {relationship.counterparty.displayName}.</Text>
    <ScrollView horizontal accessibilityLabel="Lọc trạng thái đề xuất" contentContainerStyle={styles.row}>
      {filters.map((filter) => <WorkoutButton key={filter.label} label={filter.label} secondary={status !== filter.status}
        onPress={() => setStatus(filter.status)} />)}
    </ScrollView>
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ." /> : null}
    {query.isError ? <WorkoutState testID="trainer-proposals-error" title="Không thể tải đề xuất" message={proposalErrorMessage(query.error)} onRetry={denied ? undefined : () => query.refetch()} /> : null}
    {!query.isPending && !query.isError && items.length === 0 ? <WorkoutState testID="trainer-proposals-empty" title="Không có đề xuất" message={status ? 'Không có đề xuất ở trạng thái này.' : 'Bạn chưa tạo đề xuất lịch tập cho học viên này.'} /> : null}
    {!query.isError ? items.map((proposal) => <WorkoutCard key={proposal.proposalId} testID={`trainer-proposal-${proposal.proposalId}`}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposal.sourcePlanName?.trim() || 'Chưa có tên kế hoạch'}</Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{trainerProposalStatusLabels[proposal.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{proposal.items.length} buổi · Phiên bản {proposal.sourcePlanVersionNumber}</Text>
      {proposal.items[0] ? <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi đầu: {formatProposalLocal(proposal.items[0].localStart, proposal.items[0].startUtcOffset, proposal.timezone)}</Text> : null}
      <WorkoutButton label="Xem chi tiết" onPress={() => router.push(`/coaching/${relationshipId}/schedule-proposals/${proposal.proposalId}` as never)} />
    </WorkoutCard>) : null}
    {query.hasNextPage ? <WorkoutButton label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm'} secondary busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView>;
}
