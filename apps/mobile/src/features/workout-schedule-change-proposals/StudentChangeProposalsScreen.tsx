import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { ApiError } from '@/types/auth';
import type { WorkoutScheduleChangeProposalStatus } from '@/types/workoutScheduleChangeProposal';
import { changeProposalErrorMessage, changeTypeLabels, formatChangeTime, studentChangeProposalStatusLabels } from './changeProposalPresentation';
import { useStudentWorkoutScheduleChangeProposals, workoutScheduleChangeProposalQueryKeys } from './workoutScheduleChangeProposalQueries';

const filters: { label: string; status?: WorkoutScheduleChangeProposalStatus }[] = [
  { label: 'Tất cả' }, { label: 'Đang chờ', status: 'PENDING' }, { label: 'Đã chấp nhận', status: 'ACCEPTED' },
  { label: 'Đã từ chối', status: 'REJECTED' }, { label: 'Đã rút', status: 'WITHDRAWN' },
];

export function StudentChangeProposalsScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const [status, setStatus] = useState<WorkoutScheduleChangeProposalStatus | undefined>();
  const [permissionLost, setPermissionLost] = useState(false);
  const query = useStudentWorkoutScheduleChangeProposals(studentId, status, allowed && !permissionLost);
  const denied = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const items = useMemo(() => query.data?.pages.flatMap((page) => page.items)
    .filter((item) => item.studentId === studentId) ?? [], [query.data, studentId]);

  useEffect(() => {
    if (!allowed || denied) queueMicrotask(() => {
      client.removeQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.student(studentId) });
      if (denied) setPermissionLost(true);
    });
  }, [allowed, client, denied, studentId]);

  useEffect(() => () => {
    client.removeQueries({ queryKey: workoutScheduleChangeProposalQueryKeys.student(studentId) });
  }, [client, studentId]);

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem đề xuất thay đổi lịch tập." />;
  if (permissionLost || denied) return <WorkoutState testID="student-change-proposals-denied" title="Không thể xem đề xuất" message="Bạn không còn quyền xem dữ liệu này. Danh sách đã lưu trên thiết bị được ẩn." />;

  return <ScrollView testID="student-change-proposals" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Đề xuất đổi hoặc hủy lịch tập</Text>
    <WorkoutButton label="Quay lại lịch tập" secondary onPress={() => router.back()} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Huấn luyện viên chỉ đề xuất thay đổi một buổi. Bạn quyết định chấp nhận hoặc từ chối; lịch hiện tại chưa thay đổi khi đề xuất còn chờ.</Text>
    <ScrollView horizontal accessibilityLabel="Lọc trạng thái đề xuất thay đổi lịch" contentContainerStyle={styles.row}>
      {filters.map((filter) => <WorkoutButton key={filter.label} label={filter.label} secondary={status !== filter.status}
        onPress={() => setStatus(filter.status)} />)}
    </ScrollView>
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ." /> : null}
    {query.isError && !denied ? <WorkoutState testID="student-change-proposals-error" title="Không thể tải đề xuất" message={changeProposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && items.length === 0 ? <WorkoutState testID="student-change-proposals-empty" title="Chưa có đề xuất" message={status ? 'Không có đề xuất ở trạng thái đã chọn.' : 'Khi huấn luyện viên gửi đề xuất đổi hoặc hủy lịch, bạn sẽ thấy tại đây.'} /> : null}
    {!query.isError ? items.map((proposal) => <WorkoutCard key={proposal.proposalId} testID={`student-change-proposal-${proposal.proposalId}`}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>{proposal.sourceSessionName?.trim() || 'Buổi tập theo lịch'}</Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{changeTypeLabels[proposal.changeType]} · {studentChangeProposalStatusLabels[proposal.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Từ: {proposal.trainerDisplayName?.trim() || 'Huấn luyện viên chưa có tên hiển thị'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Lịch hiện tại: {formatChangeTime(proposal.oldPlannedStartAt, proposal.oldTimezone)}</Text>
      {proposal.destination ? <Text style={[styles.body, { color: theme.textSecondary }]}>Giờ đề xuất: {proposal.destination.localStart.replace('T', ' ')} UTC{proposal.destination.startUtcOffset} · {proposal.destination.timezone}</Text> : null}
      <WorkoutButton testID={`student-open-change-proposal-${proposal.proposalId}`} label="Xem chi tiết đề xuất"
        onPress={() => router.push(`/workout-change-proposals/${proposal.proposalId}` as never)} />
    </WorkoutCard>) : null}
    {query.hasNextPage ? <WorkoutButton testID="student-change-proposals-load-more" label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm đề xuất'} secondary busy={query.isFetchingNextPage}
      disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView>;
}
