import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import {
  WORKOUT_EXECUTION_PAGE_SIZE,
  workoutExecutionApi,
} from '@/services/workoutExecutionApi';

export const workoutExecutionQueryKeys = {
  all: ['workout-executions'] as const,
  student: (studentId: string) => [...workoutExecutionQueryKeys.all, 'student', studentId] as const,
  current: (studentId: string) => [...workoutExecutionQueryKeys.student(studentId), 'current'] as const,
  history: (studentId: string) => [...workoutExecutionQueryKeys.student(studentId), 'history'] as const,
  detail: (studentId: string, executionId: string) =>
    [...workoutExecutionQueryKeys.student(studentId), 'detail', executionId] as const,
};

export const currentWorkoutExecutionOptions = (studentId: string, enabled = true) =>
  queryOptions({
    queryKey: workoutExecutionQueryKeys.current(studentId),
    queryFn: () => workoutExecutionApi.current(),
    enabled: Boolean(studentId) && enabled,
    staleTime: 5_000,
  });

export const workoutExecutionHistoryOptions = (studentId: string, enabled = true) =>
  infiniteQueryOptions({
    queryKey: workoutExecutionQueryKeys.history(studentId),
    queryFn: ({ pageParam }) =>
      workoutExecutionApi.history(pageParam, WORKOUT_EXECUTION_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (page) =>
      page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    enabled: Boolean(studentId) && enabled,
    staleTime: 10_000,
  });

export const workoutExecutionDetailOptions = (
  studentId: string,
  executionId: string,
  enabled = true
) =>
  queryOptions({
    queryKey: workoutExecutionQueryKeys.detail(studentId, executionId),
    queryFn: () => workoutExecutionApi.detail(executionId),
    enabled: Boolean(studentId && executionId) && enabled,
    staleTime: 5_000,
  });

export const useCurrentWorkoutExecution = (studentId: string, enabled = true) =>
  useQuery(currentWorkoutExecutionOptions(studentId, enabled));
export const useWorkoutExecutionHistory = (studentId: string, enabled = true) =>
  useInfiniteQuery(workoutExecutionHistoryOptions(studentId, enabled));
export const useWorkoutExecutionDetail = (
  studentId: string,
  executionId: string,
  enabled = true
) => useQuery(workoutExecutionDetailOptions(studentId, executionId, enabled));
