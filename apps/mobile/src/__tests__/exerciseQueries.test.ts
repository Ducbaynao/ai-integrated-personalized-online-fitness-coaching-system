import { exerciseApi } from '@/services/exerciseApi';
import {
  exerciseCatalogInfiniteQueryOptions,
  exerciseQueryKeys,
} from '@/features/exercise/exerciseQueries';
import { shouldRetryQuery } from '@/services/queryClient';
import { ApiError } from '@/types/auth';
import { ExerciseCatalogPage } from '@/types/exercise';

jest.mock('@/services/exerciseApi', () => {
  const actual = jest.requireActual('@/services/exerciseApi');
  return {
    ...actual,
    exerciseApi: {
      ...actual.exerciseApi,
      getCatalog: jest.fn(),
    },
  };
});

const emptyPage = (page: number, totalPages: number): ExerciseCatalogPage => ({
  items: [],
  page,
  size: 20,
  totalItems: totalPages * 20,
  totalPages,
});

describe('exercise catalog query policy', () => {
  beforeEach(() => jest.clearAllMocks());

  it('builds stable normalized keys regardless of filter order and casing', () => {
    const first = exerciseQueryKeys.list({
      query: ' Squat ',
      categoryCodes: ['strength', 'mobility'],
    });
    const second = exerciseQueryKeys.list({
      query: 'squat',
      categoryCodes: ['MOBILITY', 'STRENGTH'],
    });

    expect(first).toEqual(second);
  });

  it('starts at page zero and derives the next page from the response', async () => {
    const options = exerciseCatalogInfiniteQueryOptions({ categoryCodes: ['strength'] });
    (exerciseApi.getCatalog as jest.Mock).mockResolvedValueOnce(emptyPage(0, 3));

    await (options.queryFn as Function)({ pageParam: options.initialPageParam });

    expect(exerciseApi.getCatalog).toHaveBeenCalledWith({
      categoryCodes: ['STRENGTH'],
      muscleGroupCodes: [],
      equipmentCodes: [],
      tagCodes: [],
      difficulties: [],
      movementPatterns: [],
      page: 0,
      size: 20,
    });
    expect(options.getNextPageParam(emptyPage(0, 3), [], 0, [])).toBe(1);
    expect(options.getNextPageParam(emptyPage(2, 3), [], 2, [])).toBeUndefined();
  });

  it('does not retry permission/not-found errors and bounds network/server retries', () => {
    expect(shouldRetryQuery(0, new ApiError(401, 'Unauthorized'))).toBe(false);
    expect(shouldRetryQuery(0, new ApiError(403, 'Forbidden'))).toBe(false);
    expect(shouldRetryQuery(0, new ApiError(404, 'Missing'))).toBe(false);
    expect(shouldRetryQuery(0, new ApiError(400, 'Bad request'))).toBe(false);
    expect(shouldRetryQuery(0, new ApiError(0, 'Offline'))).toBe(true);
    expect(shouldRetryQuery(1, new ApiError(503, 'Unavailable'))).toBe(true);
    expect(shouldRetryQuery(2, new ApiError(503, 'Unavailable'))).toBe(false);
  });
});

