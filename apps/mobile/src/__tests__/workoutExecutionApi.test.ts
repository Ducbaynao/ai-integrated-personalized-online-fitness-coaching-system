import { request } from '@/services/apiClient';
import {
  normalizeWorkoutExecutionId,
  workoutExecutionApi,
} from '@/services/workoutExecutionApi';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

const setInput = {
  exerciseExecutionId: 'exercise-execution-1',
  baselineSetNumber: 1,
  setNumber: 1,
  setType: 'WORKING' as const,
  completionStatus: 'COMPLETED' as const,
  repetitions: 8,
  loadValue: 60,
  loadUnitId: 1,
  durationSeconds: null,
  distanceValue: null,
  distanceUnitId: null,
  rpe: 8,
  rir: null,
  tempo: null,
  restAfterSeconds: 90,
  note: null,
};

describe('workoutExecutionApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (request as jest.Mock).mockResolvedValue({});
  });

  it('uses current, history and detail read contracts', async () => {
    await workoutExecutionApi.current();
    await workoutExecutionApi.history(2, 20);
    await workoutExecutionApi.detail('execution-1');

    expect(request).toHaveBeenNthCalledWith(1, '/workout-executions/current', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-executions?page=2&size=20', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(3, '/workout-executions/execution-1', { method: 'GET' });
  });

  it('keeps nullable unit presentation from execution reads without replacing unit identity', async () => {
    const response = {
      execution: { executionId: 'execution-1' },
      exercises: [
        {
          exerciseExecutionId: 'exercise-execution-1',
          loadUnitId: 41,
          loadUnit: { id: 41, code: 'KG', symbol: 'kg', dimension: 'MASS' },
          distanceUnitId: 92,
          distanceUnit: null,
          sets: [
            {
              clientSetId: 'set-1',
              loadUnitId: 77,
              loadUnit: { id: 77, code: 'LB', symbol: 'lb', dimension: 'MASS' },
            },
          ],
        },
      ],
    };
    (request as jest.Mock).mockResolvedValueOnce(response);

    await expect(workoutExecutionApi.current()).resolves.toEqual(response);
    expect(request).toHaveBeenCalledWith('/workout-executions/current', { method: 'GET' });
  });

  it('serializes set and substitution writes with the supplied authoritative version', async () => {
    await workoutExecutionApi.upsertSet('execution-1', 'set-1', 7, setInput);
    await workoutExecutionApi.substitute('execution-1', 'exercise-execution-1', 8, {
      actualExerciseVariationId: 'variation-2',
      substitutionReason: 'Không có thiết bị',
    });

    expect(request).toHaveBeenNthCalledWith(
      1,
      '/workout-executions/execution-1/sets/set-1',
      { method: 'PUT', body: { expectedVersion: 7, ...setInput } }
    );
    expect(request).toHaveBeenNthCalledWith(
      2,
      '/workout-executions/execution-1/exercises/exercise-execution-1',
      {
        method: 'PUT',
        body: {
          expectedVersion: 8,
          actualExerciseVariationId: 'variation-2',
          substitutionReason: 'Không có thiết bị',
        },
      }
    );
  });

  it('keeps terminal command keys and expected versions supplied by the caller', async () => {
    const input = { overallRpe: 8, sessionNote: 'Ổn định' };
    await workoutExecutionApi.complete('execution-1', 9, input, 'complete-key');
    await workoutExecutionApi.abort('execution-1', 10, input, 'abort-key');

    expect(request).toHaveBeenNthCalledWith(1, '/workout-executions/execution-1/complete', {
      method: 'POST',
      body: { expectedVersion: 9, commandKey: 'complete-key', ...input },
    });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-executions/execution-1/abort', {
      method: 'POST',
      body: { expectedVersion: 10, commandKey: 'abort-key', ...input },
    });
  });

  it('normalizes UUID route values and rejects malformed IDs', () => {
    expect(
      normalizeWorkoutExecutionId(' 10000000-0000-4000-8000-00000000000A ')
    ).toBe('10000000-0000-4000-8000-00000000000a');
    expect(normalizeWorkoutExecutionId('execution-1')).toBeNull();
  });
});
