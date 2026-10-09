import React, { useMemo } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { useAuth } from '@/features/auth/AuthContext';
import {
  ExecutionButton,
  ExecutionCard,
  ExecutionState,
  executionStyles as styles,
} from './components/WorkoutExecutionUi';
import {
  useWorkoutExecutionHistory,
} from './workoutExecutionQueries';
import {
  supervisionLabels,
  workoutExecutionErrorMessage,
  workoutExecutionStatusLabels,
} from './workoutExecutionMessages';

function formatDate(value: string | null, timezone?: string): string {
  if (!value) return 'Chưa có dữ liệu';
  return new Date(value).toLocaleString('vi-VN', timezone ? { timeZone: timezone } : undefined);
}

export function WorkoutHistoryScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const query = useWorkoutExecutionHistory(
    studentId,
    activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile)
  );
  const items = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data]);

  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) {
    return (
      <ExecutionState
        title="Chỉ dành cho Học viên"
        message="Hãy chuyển sang hồ sơ Học viên để xem lịch sử tập luyện của bạn."
      />
    );
  }

  return (
    <ScrollView
      testID="workout-history-screen"
      style={[styles.screen, { backgroundColor: theme.canvas }]}
      contentContainerStyle={styles.content}>
      <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>
        Lịch sử tập luyện
      </Text>
      <ExecutionButton label="Quay lại kế hoạch" secondary onPress={() => router.back()} />
      {query.isPending ? (
        <ExecutionState
          busy
          title="Đang tải lịch sử"
          message="Vui lòng chờ trong giây lát."
        />
      ) : null}
      {query.isError ? (
        <ExecutionState
          testID="workout-history-error"
          title="Không thể tải lịch sử"
          message={workoutExecutionErrorMessage(query.error)}
          onRetry={() => query.refetch()}
        />
      ) : null}
      {!query.isPending && !query.isError && items.length === 0 ? (
        <ExecutionState
          testID="workout-history-empty"
          title="Chưa có buổi tập đã ghi"
          message="Các buổi tập đã bắt đầu sẽ xuất hiện tại đây."
        />
      ) : null}
      {items.map((item) => (
        <ExecutionCard key={item.executionId} testID={`workout-history-${item.executionId}`}>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>
            {workoutExecutionStatusLabels[item.status]}
          </Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Bắt đầu thực tế: {formatDate(item.performedStartedAt, user?.timezone)}
          </Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Thời gian dự kiến: {formatDate(item.plannedStartAt, user?.timezone)}
          </Text>
          {item.supervisionRequirement ? (
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              {supervisionLabels[item.supervisionRequirement]}
            </Text>
          ) : null}
          {item.snapshotMode === 'LEGACY_REFERENCE_ONLY' ? (
            <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>
              Bản ghi cũ có thể không có đầy đủ dữ liệu kế hoạch ban đầu.
            </Text>
          ) : null}
          <ExecutionButton
            label="Xem chi tiết buổi tập"
            secondary
            onPress={() => router.push(`/workouts/${item.executionId}` as never)}
          />
        </ExecutionCard>
      ))}
      {query.hasNextPage ? (
        <ExecutionButton
          label={query.isFetchingNextPage ? 'Đang tải thêm' : 'Tải thêm lịch sử'}
          busy={query.isFetchingNextPage}
          disabled={query.isFetchingNextPage}
          secondary
          onPress={() => query.fetchNextPage()}
        />
      ) : null}
    </ScrollView>
  );
}
