import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { workoutPlanApi, WORKOUT_PLAN_PAGE_SIZE } from '@/services/workoutPlanApi';

export const workoutPlanQueryKeys = {
  all: ['workout-plans'] as const,
  student: (studentId: string) => [...workoutPlanQueryKeys.all, 'student', studentId] as const,
  list: (studentId: string) => [...workoutPlanQueryKeys.student(studentId), 'list'] as const,
  current: (studentId: string) => [...workoutPlanQueryKeys.student(studentId), 'current'] as const,
  detail: (studentId: string, planId: string) => [...workoutPlanQueryKeys.student(studentId), 'detail', planId] as const,
  versions: (studentId: string, planId: string) => [...workoutPlanQueryKeys.detail(studentId, planId), 'versions'] as const,
  version: (studentId: string, planId: string, versionId: string) => [...workoutPlanQueryKeys.versions(studentId, planId), versionId] as const,
};

export const workoutPlanListOptions = (studentId: string) => infiniteQueryOptions({
  queryKey: workoutPlanQueryKeys.list(studentId), queryFn: ({ pageParam }) => workoutPlanApi.list(pageParam, WORKOUT_PLAN_PAGE_SIZE),
  initialPageParam: 0, getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: Boolean(studentId), staleTime: 10_000,
});
export const currentWorkoutPlanOptions = (studentId: string) => queryOptions({
  queryKey: workoutPlanQueryKeys.current(studentId), queryFn: () => workoutPlanApi.current(), enabled: Boolean(studentId), staleTime: 10_000,
});
export const workoutPlanDetailOptions = (studentId: string, planId: string) => queryOptions({
  queryKey: workoutPlanQueryKeys.detail(studentId, planId), queryFn: () => workoutPlanApi.detail(planId), enabled: Boolean(studentId && planId), staleTime: 10_000,
});
export const workoutPlanVersionsOptions = (studentId: string, planId: string) => infiniteQueryOptions({
  queryKey: workoutPlanQueryKeys.versions(studentId, planId), queryFn: ({ pageParam }) => workoutPlanApi.versions(planId, pageParam, WORKOUT_PLAN_PAGE_SIZE),
  initialPageParam: 0, getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: Boolean(studentId && planId), staleTime: 10_000,
});
export const workoutPlanVersionOptions = (studentId: string, planId: string, versionId: string) => queryOptions({
  queryKey: workoutPlanQueryKeys.version(studentId, planId, versionId), queryFn: () => workoutPlanApi.version(planId, versionId),
  enabled: Boolean(studentId && planId && versionId), staleTime: 30_000,
});

export const useWorkoutPlans = (studentId: string) => useInfiniteQuery(workoutPlanListOptions(studentId));
export const useCurrentWorkoutPlan = (studentId: string) => useQuery(currentWorkoutPlanOptions(studentId));
export const useWorkoutPlan = (studentId: string, planId: string) => useQuery(workoutPlanDetailOptions(studentId, planId));
export const useWorkoutPlanVersions = (studentId: string, planId: string) => useInfiniteQuery(workoutPlanVersionsOptions(studentId, planId));
export const useWorkoutPlanVersion = (studentId: string, planId: string, versionId: string) => useQuery(workoutPlanVersionOptions(studentId, planId, versionId));
