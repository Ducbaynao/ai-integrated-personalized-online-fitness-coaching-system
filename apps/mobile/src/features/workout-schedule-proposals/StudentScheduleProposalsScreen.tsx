import React, { useMemo } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { useWorkoutScheduleProposals } from './workoutScheduleProposalQueries';
import { proposalErrorMessage, proposalStatusLabels, formatProposalLocal } from './proposalPresentation';

export function StudentScheduleProposalsScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const query = useWorkoutScheduleProposals(user?.id ?? '', allowed);
  const items = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);

  if (!allowed) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem đề xuất lịch tập." />;

  return <ScrollView testID="student-schedule-proposals-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Đề xuất lịch tập</Text>
    <WorkoutButton label="Quay lại kế hoạch" secondary onPress={() => router.back()} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Huấn luyện viên có thể đề xuất lịch. Chỉ bạn mới có thể chấp nhận hoặc từ chối toàn bộ đề xuất.</Text>
    {query.isPending ? <WorkoutState busy title="Đang tải đề xuất" message="Vui lòng chờ trong giây lát." /> : null}
    {query.isError ? <WorkoutState testID="proposals-error" title="Không thể tải đề xuất" message={proposalErrorMessage(query.error)} onRetry={() => query.refetch()} /> : null}
    {!query.isPending && !query.isError && items.length === 0 ? <WorkoutState testID="proposals-empty" title="Chưa có đề xuất lịch tập" message="Khi huấn luyện viên gửi đề xuất, bạn sẽ thấy tại đây." /> : null}
    {items.map((proposal) => <WorkoutCard key={proposal.proposalId} testID={`proposal-${proposal.proposalId}`}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Đề xuất từ huấn luyện viên</Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{proposalStatusLabels[proposal.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Mã huấn luyện viên: {proposal.trainerId}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Kế hoạch: {proposal.planId} · Phiên bản {proposal.sourcePlanVersionNumber}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{proposal.items.length} buổi được đề xuất · {proposal.timezone}</Text>
      {proposal.items[0] ? <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi đầu: {formatProposalLocal(proposal.items[0].localStart, proposal.items[0].startUtcOffset, proposal.timezone)}</Text> : null}
      <WorkoutButton testID={`open-proposal-${proposal.proposalId}`} label="Xem chi tiết đề xuất" onPress={() => router.push(`/workout-schedule-proposals/${proposal.proposalId}` as never)} />
    </WorkoutCard>)}
    {query.hasNextPage ? <WorkoutButton testID="proposals-load-more" label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm đề xuất'} secondary busy={query.isFetchingNextPage} disabled={query.isFetchingNextPage} onPress={() => query.fetchNextPage()} /> : null}
  </ScrollView>;
}
