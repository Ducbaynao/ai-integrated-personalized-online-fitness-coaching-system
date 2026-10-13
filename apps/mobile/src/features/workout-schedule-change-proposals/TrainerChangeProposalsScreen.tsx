import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { ApiError } from '@/types/auth';
import type { WorkoutScheduleChangeProposalStatus } from '@/types/workoutScheduleChangeProposal';
import { changeProposalErrorMessage, changeProposalStatusLabels, changeTypeLabels, formatChangeTime } from './changeProposalPresentation';
import { useTrainerWorkoutScheduleChangeProposals } from './workoutScheduleChangeProposalQueries';
import { useTrainerChangeProposalAuthority } from './useTrainerChangeProposalAuthority';

const filters: { label: string; status?: WorkoutScheduleChangeProposalStatus }[] = [
  { label: 'Tất cả' }, { label: 'Đang chờ', status: 'PENDING' }, { label: 'Đã chấp nhận', status: 'ACCEPTED' },
  { label: 'Đã từ chối', status: 'REJECTED' }, { label: 'Đã rút', status: 'WITHDRAWN' },
];

export function TrainerChangeProposalsScreen({ relationshipId }: { relationshipId: string }) {
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const authority = useTrainerChangeProposalAuthority(relationshipId);
  const [status, setStatus] = useState<WorkoutScheduleChangeProposalStatus | undefined>();
  const query = useTrainerWorkoutScheduleChangeProposals(authority.trainerId, authority.studentId, status, authority.canView);
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const items = useMemo(() => query.data?.pages.flatMap((page) => page.items).filter(
    (item) => item.trainerId === authority.trainerId && item.studentId === authority.studentId,
  ) ?? [], [authority.studentId, authority.trainerId, query.data]);

  useEffect(() => {
    if (denied) queueMicrotask(authority.deny);
  }, [authority.deny, denied]);

  if (authority.activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Hãy chuyển sang hồ sơ huấn luyện viên." />;
  if (authority.relationshipQuery.isPending || authority.sharingQuery.isPending || authority.verifying)
    return <WorkoutState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." />;
  if (!authority.canView || denied || authority.relationshipQuery.isError || authority.sharingQuery.isError)
    return <WorkoutState testID="change-proposals-denied" title="Không thể xem đề xuất" message="Quan hệ hoặc quyền xem kế hoạch không còn hiệu lực." onRetry={authority.retry} />;

  return <ScrollView testID="trainer-change-proposals" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Đề xuất đổi hoặc hủy lịch</Text>
    <WorkoutButton label="Quay lại học viên" secondary onPress={() => router.back()} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ đề xuất bạn đã tạo cho {authority.relationship?.counterparty.displayName || 'học viên này'}. Học viên là người chấp nhận hoặc từ chối.</Text>
    {authority.canContribute ? <WorkoutButton testID="change-proposals-open-calendar" label="Chọn buổi tập từ lịch để đề xuất" secondary onPress={() => router.push(`/coaching/${relationshipId}/workout-schedule` as never)} /> : null}
    <ScrollView horizontal accessibilityLabel="Lọc trạng thái đề xuất đổi lịch" contentContainerStyle={styles.row}>
      {filters.map((filter) => <WorkoutButton key={filter.label} label={filter.label} secondary={status !== filter.status} onPress={() => setStatus(filter.status)} />)}
    </ScrollView>
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ." /> : null}
    {query.isError && !denied ? <WorkoutState testID="change-proposals-error" title="Không thể tải đề xuất" message={changeProposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && items.length === 0 ? <WorkoutState testID="change-proposals-empty" title="Không có đề xuất" message={status ? 'Không có đề xuất ở trạng thái này.' : 'Bạn chưa tạo đề xuất đổi hoặc hủy lịch cho học viên này.'} /> : null}
    {!query.isError ? items.map((proposal) => <WorkoutCard key={proposal.proposalId} testID={`change-proposal-${proposal.proposalId}`}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposal.sourceSessionName?.trim() || 'Buổi tập theo lịch'}</Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{changeTypeLabels[proposal.changeType]} · {changeProposalStatusLabels[proposal.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {proposal.sourcePlanName?.trim() || 'Chưa có tên kế hoạch'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ cũ: {formatChangeTime(proposal.oldPlannedStartAt, proposal.oldTimezone)}</Text>
      <WorkoutButton testID={`open-change-proposal-${proposal.proposalId}`} label="Xem chi tiết đề xuất" onPress={() => router.push(`/coaching/${relationshipId}/change-proposals/${proposal.proposalId}` as never)} />
    </WorkoutCard>) : null}
    {query.hasNextPage ? <WorkoutButton testID="change-proposals-load-more" label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm đề xuất'} secondary busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView>;
}
