import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import {
  EXERCISE_CATALOG_PAGE_SIZE,
  exerciseApi,
  normalizeExerciseCatalogFilters,
  normalizeExerciseId,
} from '@/services/exerciseApi';
import { shouldRetryQuery } from '@/services/queryClient';
import { ExerciseCatalogFilters } from '@/types/exercise';

export const EXERCISE_CATALOG_STALE_TIME = 60_000;
export const EXERCISE_DETAIL_STALE_TIME = 5 * 60_000;
export const EXERCISE_METADATA_STALE_TIME = 30 * 60_000;

export const exerciseQueryKeys = {
  all: ['exercise-catalog'] as const,
  list: (filters: ExerciseCatalogFilters = {}) =>
    [...exerciseQueryKeys.all, 'list', normalizeExerciseCatalogFilters(filters)] as const,
  detail: (exerciseId: string | null) =>
    [...exerciseQueryKeys.all, 'detail', exerciseId ?? 'invalid'] as const,
  metadata: () => [...exerciseQueryKeys.all, 'filter-metadata'] as const,
};

export function exerciseCatalogInfiniteQueryOptions(filters: ExerciseCatalogFilters = {}) {
  const normalized = normalizeExerciseCatalogFilters(filters);
  return infiniteQueryOptions({
    queryKey: exerciseQueryKeys.list(normalized),
    queryFn: ({ pageParam }) =>
      exerciseApi.getCatalog({
        ...normalized,
        page: pageParam,
        size: EXERCISE_CATALOG_PAGE_SIZE,
      }),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
    staleTime: EXERCISE_CATALOG_STALE_TIME,
    retry: shouldRetryQuery,
  });
}

export function useExerciseCatalog(filters: ExerciseCatalogFilters = {}) {
  return useInfiniteQuery(exerciseCatalogInfiniteQueryOptions(filters));
}

export function exerciseDetailQueryOptions(exerciseId: unknown) {
  const normalizedExerciseId = normalizeExerciseId(exerciseId);
  return queryOptions({
    queryKey: exerciseQueryKeys.detail(normalizedExerciseId),
    queryFn: () => {
      if (!normalizedExerciseId) {
        return Promise.reject(new RangeError('exerciseId must be a valid UUID'));
      }
      return exerciseApi.getDetail(normalizedExerciseId);
    },
    enabled: normalizedExerciseId !== null,
    staleTime: EXERCISE_DETAIL_STALE_TIME,
    retry: shouldRetryQuery,
  });
}

export function useExerciseDetail(exerciseId: unknown) {
  return useQuery(exerciseDetailQueryOptions(exerciseId));
}

export function useExerciseFilterMetadata() {
  return useQuery({
    queryKey: exerciseQueryKeys.metadata(),
    queryFn: () => exerciseApi.getFilterMetadata(),
    staleTime: EXERCISE_METADATA_STALE_TIME,
    retry: shouldRetryQuery,
  });
}

