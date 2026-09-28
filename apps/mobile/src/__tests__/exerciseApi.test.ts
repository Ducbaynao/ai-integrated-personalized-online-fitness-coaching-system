import {
  exerciseApi,
  normalizeExerciseId,
  serializeExerciseCatalogParams,
} from '@/services/exerciseApi';
import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import { EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION } from '@/types/exercise';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

describe('exerciseApi', () => {
  beforeEach(() => jest.clearAllMocks());

  it('serializes search, pagination, and repeated filter values deterministically', () => {
    const query = serializeExerciseCatalogParams({
      query: '  Squat  ',
      categoryCodes: ['strength', 'MOBILITY', 'strength'],
      muscleGroupCodes: ['quadriceps', 'glutes'],
      equipmentCodes: ['bodyweight', 'dumbbell'],
      tagCodes: ['compound', 'beginner_friendly'],
      difficulties: ['INTERMEDIATE', 'BEGINNER'],
      movementPatterns: ['squat', 'hinge'],
      page: 2,
      size: 20,
    });

    expect(query).toBe(
      'query=squat&categoryCodes=MOBILITY&categoryCodes=STRENGTH' +
        '&muscleGroupCodes=GLUTES&muscleGroupCodes=QUADRICEPS' +
        '&equipmentCodes=BODYWEIGHT&equipmentCodes=DUMBBELL' +
        '&tagCodes=BEGINNER_FRIENDLY&tagCodes=COMPOUND' +
        '&difficulties=BEGINNER&difficulties=INTERMEDIATE' +
        '&movementPatterns=HINGE&movementPatterns=SQUAT&page=2&size=20'
    );
  });

  it('uses the B01 request client for list, detail, and filter metadata', async () => {
    (request as jest.Mock)
      .mockResolvedValueOnce({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 })
      .mockResolvedValueOnce({ id: '10000000-0000-4000-8000-000000000001' })
      .mockResolvedValueOnce({ categories: [] });

    await exerciseApi.getCatalog();
    await exerciseApi.getDetail('10000000-0000-4000-8000-000000000001');
    await exerciseApi.getFilterMetadata();

    expect(request).toHaveBeenNthCalledWith(1, '/exercises?page=0&size=20', {
      method: 'GET',
      requiresAuth: true,
    });
    expect(request).toHaveBeenNthCalledWith(
      2,
      '/exercises/10000000-0000-4000-8000-000000000001',
      {
      method: 'GET',
      requiresAuth: true,
      }
    );
    expect(request).toHaveBeenNthCalledWith(3, '/exercises/filter-metadata', {
      method: 'GET',
      requiresAuth: true,
    });
  });

  it.each([
    'categoryCodes',
    'muscleGroupCodes',
    'equipmentCodes',
    'tagCodes',
    'difficulties',
    'movementPatterns',
  ] as const)('rejects %s above the OpenAPI maxItems before creating a request', (dimension) => {
    const values = Array.from(
      { length: EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION + 1 },
      (_, index) => `VALUE_${index}`
    );

    expect(() => serializeExerciseCatalogParams({ [dimension]: values })).toThrow(RangeError);
    expect(request).not.toHaveBeenCalled();
  });

  it('does not call the request client when a filter dimension exceeds maxItems', async () => {
    const categoryCodes = Array.from(
      { length: EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION + 1 },
      (_, index) => `CATEGORY_${index}`
    );

    await expect(exerciseApi.getCatalog({ categoryCodes })).rejects.toThrow(RangeError);
    expect(request).not.toHaveBeenCalled();
  });

  it.each([undefined, '', 'exercise-one', ['10000000-0000-4000-8000-000000000001']])(
    'rejects an invalid detail identifier without sending a request: %p',
    async (exerciseId) => {
      expect(normalizeExerciseId(exerciseId)).toBeNull();
      await expect(exerciseApi.getDetail(exerciseId as string)).rejects.toThrow(RangeError);
      expect(request).not.toHaveBeenCalled();
    }
  );

  it('normalizes a valid UUID before requesting detail', async () => {
    (request as jest.Mock).mockResolvedValueOnce({});
    const id = '10000000-0000-4000-8000-00000000000A';

    await exerciseApi.getDetail(` ${id} `);

    expect(request).toHaveBeenCalledWith(
      '/exercises/10000000-0000-4000-8000-00000000000a',
      { method: 'GET', requiresAuth: true }
    );
  });

  it('accepts the UUID-shaped synthetic identifier documented by OpenAPI', () => {
    expect(normalizeExerciseId('10000000-0000-0000-0000-000000000001')).toBe(
      '10000000-0000-0000-0000-000000000001'
    );
  });

  it('propagates stable authentication errors from request()', async () => {
    const error = new ApiError(403, 'Forbidden');
    (request as jest.Mock).mockRejectedValueOnce(error);

    await expect(exerciseApi.getCatalog()).rejects.toBe(error);
  });
});

