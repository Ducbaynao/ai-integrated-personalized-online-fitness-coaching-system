import { request } from '@/services/apiClient';
import {
  FinishWorkoutExecutionInput,
  SubstituteWorkoutExerciseInput,
  UpsertWorkoutSetInput,
  WorkoutExecutionDetail,
  WorkoutExecutionPage,
} from '@/types/workoutExecution';

export const WORKOUT_EXECUTION_PAGE_SIZE = 20;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function normalizeWorkoutExecutionId(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toLowerCase();
  return UUID_PATTERN.test(normalized) ? normalized : null;
}

const pageQuery = (page: number, size: number) => `page=${page}&size=${size}`;

export const workoutExecutionApi = {
  current() {
    return request<WorkoutExecutionDetail>('/workout-executions/current', { method: 'GET' });
  },
  history(page = 0, size = WORKOUT_EXECUTION_PAGE_SIZE) {
    return request<WorkoutExecutionPage>(`/workout-executions?${pageQuery(page, size)}`, {
      method: 'GET',
    });
  },
  detail(executionId: string) {
    return request<WorkoutExecutionDetail>(`/workout-executions/${executionId}`, { method: 'GET' });
  },
  upsertSet(
    executionId: string,
    clientSetId: string,
    expectedVersion: number,
    input: UpsertWorkoutSetInput
  ) {
    return request<WorkoutExecutionDetail>(
      `/workout-executions/${executionId}/sets/${clientSetId}`,
      { method: 'PUT', body: { expectedVersion, ...input } }
    );
  },
  substitute(
    executionId: string,
    exerciseExecutionId: string,
    expectedVersion: number,
    input: SubstituteWorkoutExerciseInput
  ) {
    return request<WorkoutExecutionDetail>(
      `/workout-executions/${executionId}/exercises/${exerciseExecutionId}`,
      { method: 'PUT', body: { expectedVersion, ...input } }
    );
  },
  complete(
    executionId: string,
    expectedVersion: number,
    input: FinishWorkoutExecutionInput,
    commandKey: string
  ) {
    return request<WorkoutExecutionDetail>(`/workout-executions/${executionId}/complete`, {
      method: 'POST',
      body: { expectedVersion, commandKey, ...input },
    });
  },
  abort(
    executionId: string,
    expectedVersion: number,
    input: FinishWorkoutExecutionInput,
    commandKey: string
  ) {
    return request<WorkoutExecutionDetail>(`/workout-executions/${executionId}/abort`, {
      method: 'POST',
      body: { expectedVersion, commandKey, ...input },
    });
  },
};
