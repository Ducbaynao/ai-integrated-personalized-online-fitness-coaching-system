import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { workoutScheduleApi, WORKOUT_SCHEDULE_PAGE_SIZE } from '@/services/workoutScheduleApi';

export const workoutScheduleQueryKeys = {
  all: ['planned-workouts'] as const,
  student: (studentId: string) => [...workoutScheduleQueryKeys.all, 'student', studentId] as const,
  detail: (studentId: string, occurrenceId: string) => [...workoutScheduleQueryKeys.student(studentId), 'detail', occurrenceId] as const,
  window: (studentId: string, from: string, until: string) =>
    [...workoutScheduleQueryKeys.student(studentId), from, until] as const,
  trainer: (trainerId: string, studentId: string) => [...workoutScheduleQueryKeys.all, 'trainer', trainerId, studentId] as const,
  trainerWindow: (trainerId: string, studentId: string, from: string, until: string) =>
    [...workoutScheduleQueryKeys.trainer(trainerId, studentId), from, until] as const,
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

export const useWorkoutScheduleDetail = (studentId: string, occurrenceId: string, enabled = true) =>
  useQuery(queryOptions({
    queryKey: workoutScheduleQueryKeys.detail(studentId, occurrenceId),
    queryFn: () => workoutScheduleApi.detail(occurrenceId),
    enabled: Boolean(studentId && occurrenceId) && enabled,
    staleTime: 0,
    refetchOnMount: 'always',
  }));

export const trainerWorkoutScheduleOptions = (trainerId: string, studentId: string, from: string, until: string, enabled = true) =>
  infiniteQueryOptions({
    queryKey: workoutScheduleQueryKeys.trainerWindow(trainerId, studentId, from, until),
    queryFn: ({ pageParam }) => workoutScheduleApi.listForTrainer(studentId, from, until, pageParam, WORKOUT_SCHEDULE_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    enabled: Boolean(trainerId && studentId && from && until) && enabled,
    staleTime: 0,
    refetchOnMount: 'always' as const,
  });

export const useTrainerWorkoutSchedule = (trainerId: string, studentId: string, from: string, until: string, enabled = true) =>
  useInfiniteQuery(trainerWorkoutScheduleOptions(trainerId, studentId, from, until, enabled));
