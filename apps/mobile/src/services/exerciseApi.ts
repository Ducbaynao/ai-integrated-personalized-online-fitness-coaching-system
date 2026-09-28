import { request } from '@/services/apiClient';
import {
  ExerciseCatalogFilters,
  ExerciseCatalogPage,
  ExerciseCatalogParams,
  ExerciseDetail,
  ExerciseDifficulty,
  ExerciseFilterMetadata,
  EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION,
} from '@/types/exercise';

export const EXERCISE_CATALOG_PAGE_SIZE = 20;

export interface NormalizedExerciseCatalogFilters {
  query?: string;
  categoryCodes: string[];
  muscleGroupCodes: string[];
  equipmentCodes: string[];
  tagCodes: string[];
  difficulties: ExerciseDifficulty[];
  movementPatterns: string[];
}

function normalizeCodes(values: readonly string[] | undefined, dimension: string): string[] {
  if ((values?.length ?? 0) > EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION) {
    throw new RangeError(
      `${dimension} must not contain more than ${EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION} values`
    );
  }

  return [...new Set((values ?? []).map((value) => value.trim().toUpperCase()).filter(Boolean))]
    .sort((left, right) => left.localeCompare(right, 'en'));
}

export function normalizeExerciseCatalogFilters(
  filters: ExerciseCatalogFilters = {}
): NormalizedExerciseCatalogFilters {
  const query = filters.query?.trim().toLocaleLowerCase('vi-VN') || undefined;
  return {
    ...(query ? { query } : {}),
    categoryCodes: normalizeCodes(filters.categoryCodes, 'categoryCodes'),
    muscleGroupCodes: normalizeCodes(filters.muscleGroupCodes, 'muscleGroupCodes'),
    equipmentCodes: normalizeCodes(filters.equipmentCodes, 'equipmentCodes'),
    tagCodes: normalizeCodes(filters.tagCodes, 'tagCodes'),
    difficulties: normalizeCodes(filters.difficulties, 'difficulties') as ExerciseDifficulty[],
    movementPatterns: normalizeCodes(filters.movementPatterns, 'movementPatterns'),
  };
}

export function serializeExerciseCatalogParams(params: ExerciseCatalogParams = {}): string {
  const normalized = normalizeExerciseCatalogFilters(params);
  const searchParams = new URLSearchParams();

  if (normalized.query) {
    searchParams.append('query', normalized.query);
  }

  const dimensions: [string, readonly string[]][] = [
    ['categoryCodes', normalized.categoryCodes],
    ['muscleGroupCodes', normalized.muscleGroupCodes],
    ['equipmentCodes', normalized.equipmentCodes],
    ['tagCodes', normalized.tagCodes],
    ['difficulties', normalized.difficulties],
    ['movementPatterns', normalized.movementPatterns],
  ];
  dimensions.forEach(([name, values]) => {
    values.forEach((value) => searchParams.append(name, value));
  });

  searchParams.append('page', String(params.page ?? 0));
  searchParams.append('size', String(params.size ?? EXERCISE_CATALOG_PAGE_SIZE));
  return searchParams.toString();
}

export const exerciseApi = {
  async getCatalog(params: ExerciseCatalogParams = {}): Promise<ExerciseCatalogPage> {
    return request<ExerciseCatalogPage>(`/exercises?${serializeExerciseCatalogParams(params)}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  async getDetail(exerciseId: string): Promise<ExerciseDetail> {
    return request<ExerciseDetail>(`/exercises/${encodeURIComponent(exerciseId)}`, {
      method: 'GET',
      requiresAuth: true,
    });
  },

  async getFilterMetadata(): Promise<ExerciseFilterMetadata> {
    return request<ExerciseFilterMetadata>('/exercises/filter-metadata', {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
