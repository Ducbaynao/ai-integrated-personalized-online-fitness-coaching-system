import React from 'react';
import { useLocalSearchParams } from 'expo-router';
import { ExecutionState } from '@/features/workout-execution/components/WorkoutExecutionUi';
import { WorkoutExecutionDetailScreen } from '@/features/workout-execution/WorkoutExecutionScreen';
import { normalizeWorkoutExecutionId } from '@/services/workoutExecutionApi';

export default function WorkoutExecutionDetailRoute() {
  const params = useLocalSearchParams<{ executionId?: string | string[] }>();
  const value = Array.isArray(params.executionId) ? params.executionId[0] : params.executionId;
  const executionId = normalizeWorkoutExecutionId(value);

  if (!executionId) {
    return (
      <ExecutionState
        title="Không thể mở buổi tập"
        message="Đường dẫn buổi tập không hợp lệ."
      />
    );
  }
  return <WorkoutExecutionDetailScreen executionId={executionId} />;
}
