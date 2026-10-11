import { infiniteQueryOptions, useInfiniteQuery } from '@tanstack/react-query';
import { workoutScheduleApi, WORKOUT_SCHEDULE_PAGE_SIZE } from '@/services/workoutScheduleApi';

export const workoutScheduleQueryKeys = {
  all: ['planned-workouts'] as const,
  student: (studentId: string) => [...workoutScheduleQueryKeys.all, 'student', studentId] as const,
  window: (studentId: string, from: string, until: string) =>
    [...workoutScheduleQueryKeys.student(studentId), from, until] as const,
};

export const workoutScheduleOptions = (studentId: string, from: string, until: string, enabled = true) =>
  infiniteQueryOptions({
    queryKey: workoutScheduleQueryKeys.window(studentId, from, until),
    queryFn: ({ pageParam }) => workoutScheduleApi.list(from, until, pageParam, WORKOUT_SCHEDULE_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    enabled: Boolean(studentId && from && until) && enabled,
    staleTime: 5_000,
  });

export const useWorkoutSchedule = (studentId: string, from: string, until: string, enabled = true) =>
  useInfiniteQuery(workoutScheduleOptions(studentId, from, until, enabled));
