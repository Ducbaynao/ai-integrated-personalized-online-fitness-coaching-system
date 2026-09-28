import { exerciseApi, serializeExerciseCatalogParams } from '@/services/exerciseApi';
import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';

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
      .mockResolvedValueOnce({ id: 'exercise/one' })
      .mockResolvedValueOnce({ categories: [] });

    await exerciseApi.getCatalog();
    await exerciseApi.getDetail('exercise/one');
    await exerciseApi.getFilterMetadata();

    expect(request).toHaveBeenNthCalledWith(1, '/exercises?page=0&size=20', {
      method: 'GET',
      requiresAuth: true,
    });
    expect(request).toHaveBeenNthCalledWith(2, '/exercises/exercise%2Fone', {
      method: 'GET',
      requiresAuth: true,
    });
    expect(request).toHaveBeenNthCalledWith(3, '/exercises/filter-metadata', {
      method: 'GET',
      requiresAuth: true,
    });
  });

  it('propagates stable authentication errors from request()', async () => {
    const error = new ApiError(403, 'Forbidden');
    (request as jest.Mock).mockRejectedValueOnce(error);

    await expect(exerciseApi.getCatalog()).rejects.toBe(error);
  });
});

