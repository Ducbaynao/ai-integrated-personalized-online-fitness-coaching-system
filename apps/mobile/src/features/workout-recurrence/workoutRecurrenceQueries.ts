import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { workoutRecurrenceApi } from '@/services/workoutRecurrenceApi';
import type { RecurrenceStatus } from '@/types/workoutRecurrence';

export const recurrenceKeys = {
  all: ['workout-recurrence'] as const,
  student: (studentId: string) => [...recurrenceKeys.all, 'student', studentId] as const,
  trainer: (trainerId: string, studentId: string) => [...recurrenceKeys.all, 'trainer', trainerId, studentId] as const,
  detail: (actorId: string, seriesId: string) => [...recurrenceKeys.all, 'detail', actorId, seriesId] as const,
  periods: (actorId: string, seriesId: string) => [...recurrenceKeys.detail(actorId, seriesId), 'periods'] as const,
};

export const useStudentSeries = (studentId: string, enabled: boolean) => useInfiniteQuery(infiniteQueryOptions({
  queryKey: recurrenceKeys.student(studentId),
  queryFn: ({ pageParam }) => workoutRecurrenceApi.listMine(pageParam),
  initialPageParam: 0,
  getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: !!studentId && enabled,
  staleTime: 0,
}));

export const useTrainerSeries = (trainerId: string, studentId: string, status: RecurrenceStatus | undefined, enabled: boolean) => useInfiniteQuery(infiniteQueryOptions({
  queryKey: [...recurrenceKeys.trainer(trainerId, studentId), status ?? 'ALL'],
  queryFn: ({ pageParam }) => workoutRecurrenceApi.listTrainer(studentId, pageParam, status),
  initialPageParam: 0,
  getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: !!trainerId && !!studentId && enabled,
  staleTime: 0,
}));

export const useSeriesDetail = (actorId: string, seriesId: string, enabled: boolean) => useQuery(queryOptions({
  queryKey: recurrenceKeys.detail(actorId, seriesId),
  queryFn: () => workoutRecurrenceApi.detail(seriesId),
  enabled: !!actorId && !!seriesId && enabled,
  staleTime: 0,
}));

export const useSeriesPeriods = (actorId: string, seriesId: string, enabled: boolean) => useInfiniteQuery(infiniteQueryOptions({
  queryKey: recurrenceKeys.periods(actorId, seriesId),
  queryFn: ({ pageParam }) => workoutRecurrenceApi.periods(seriesId, pageParam),
  initialPageParam: 0,
  getNextPageParam: page => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: !!actorId && !!seriesId && enabled,
  staleTime: 0,
}));
