import { aiWorkoutRescheduleApi, getWorkoutRescheduleFailureKind } from '@/services/aiWorkoutRescheduleApi';
import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import {
  AcceptRescheduleApiRequest,
  AcceptRescheduleApiResponse,
  WorkoutRescheduleApiRequest,
  WorkoutRescheduleApiResponse,
  WorkoutRescheduleDetailApiResponse,
} from '@/types/aiWorkoutReschedule';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

const mockedRequest = request as jest.Mock;

const generated: WorkoutRescheduleApiResponse = {
  recommendationId: 'rec-1',
  runId: 'run-1',
  status: 'PENDING',
  decisionAuthority: 'TRAINER',
  title: 'Chuyển buổi tập',
  explanation: 'Cần giữ thời gian phục hồi',
  expiresAt: '2026-11-19T10:30:00Z',
  createdAt: '2026-11-19T10:00:00Z',
  structuredPayload: { missingData: ['availability'], confidence: null },
};

describe('aiWorkoutRescheduleApi', () => {
  beforeEach(() => jest.clearAllMocks());

  it('sends generation fields to Spring and retains unknown payload values', async () => {
    mockedRequest.mockResolvedValueOnce(generated);
    const body: WorkoutRescheduleApiRequest = {
      occurrenceId: 'occ-1',
      studentId: 'student-1',
      expectedOccurrenceVersion: 0,
      commandKey: 'generate-intent-1',
      reason: 'Lỡ buổi tập',
      preferredTimeOfDay: 'MORNING',
    };

    await expect(aiWorkoutRescheduleApi.generate(body)).resolves.toEqual(generated);
    expect(mockedRequest).toHaveBeenCalledWith('/ai/workout-reschedules', {
      method: 'POST',
      body,
    });
    expect(JSON.parse(JSON.stringify(mockedRequest.mock.calls[0][1].body))).toEqual(body);
    expect(generated.structuredPayload.confidence).toBeNull();
  });

  it('omits optional generation fields and does not replace a missing version with zero', async () => {
    mockedRequest.mockResolvedValueOnce(generated);
    await aiWorkoutRescheduleApi.generate({ occurrenceId: 'occ-1', commandKey: 'generate-intent-2' });
    expect(JSON.parse(JSON.stringify(mockedRequest.mock.calls[0][1].body))).toEqual({
      occurrenceId: 'occ-1',
      commandKey: 'generate-intent-2',
    });
  });

  it('reads detail with revision and nullable source fields intact', async () => {
    const detail: WorkoutRescheduleDetailApiResponse = {
      id: 'rec-1', recommendationId: 'rec-1', runId: 'run-1', aiRunId: 'run-1',
      studentId: 'student-1', coachingPeriodId: null,
      recommendationType: 'WORKOUT_RESCHEDULE_PROPOSAL', title: 'Chuyển buổi tập',
      explanation: null, status: 'PENDING', decisionAuthority: 'STUDENT',
      targetEntityType: 'WORKOUT_OCCURRENCE', targetEntityId: 'occ-1',
      decidedBy: null, decidedAt: null, decisionNote: null, expiresAt: null,
      revision: 2, isCustomized: false, lastModifiedBy: null,
      createdAt: '2026-11-19T10:00:00Z', updatedAt: '2026-11-19T10:00:00Z',
      structuredPayload: { missingData: ['availability'] }, originalPayload: null,
      sharedWithStudent: false, sharedRevision: null, sharedAt: null, sharedBy: null,
    };
    mockedRequest.mockResolvedValueOnce(detail);
    await expect(aiWorkoutRescheduleApi.getRecommendation('rec/1')).resolves.toEqual(detail);
    expect(mockedRequest).toHaveBeenCalledWith('/ai/workout-reschedules/rec%2F1', { method: 'GET' });
  });

  it('always sends accept command key and all supplied guards, including version zero', async () => {
    const receipt: AcceptRescheduleApiResponse = {
      recommendationId: 'rec-1', status: 'APPLIED', appliedBy: 'trainer-1',
      resultingEntityType: 'WORKOUT_OCCURRENCE', resultingEntityId: 'occ-1',
      appliedAt: '2026-11-19T10:01:00Z', replayed: false,
      changeResult: { previousPlannedStartAt: null },
    };
    mockedRequest.mockResolvedValueOnce(receipt);
    const body: AcceptRescheduleApiRequest = {
      commandKey: 'accept-intent-1', decisionNote: 'Đồng ý', expectedRevision: 2,
      expectedOccurrenceVersion: 0, expectedAppointmentVersion: 3,
      expectedAppointmentId: 'appointment-1',
    };
    await expect(aiWorkoutRescheduleApi.accept('rec-1', body)).resolves.toEqual(receipt);
    expect(mockedRequest).toHaveBeenCalledWith('/ai/workout-reschedules/rec-1/accept', {
      method: 'POST', body,
    });
  });

  it('sends only the accept key when guards are unavailable', async () => {
    mockedRequest.mockResolvedValueOnce({ status: 'APPLIED', replayed: false });
    await aiWorkoutRescheduleApi.accept('rec-1', { commandKey: 'accept-intent-2' });
    expect(JSON.parse(JSON.stringify(mockedRequest.mock.calls[0][1].body))).toEqual({
      commandKey: 'accept-intent-2',
    });
  });

  it('rejects with or without an optional note', async () => {
    const response = { recommendationId: 'rec-1', status: 'REJECTED', rejectedAt: '2026-11-19T10:02:00Z' };
    mockedRequest.mockResolvedValue(response);
    await expect(aiWorkoutRescheduleApi.reject('rec-1', { decisionNote: 'Chưa phù hợp' })).resolves.toEqual(response);
    await aiWorkoutRescheduleApi.reject('rec-1');
    expect(mockedRequest.mock.calls[0]).toEqual(['/ai/workout-reschedules/rec-1/reject', {
      method: 'POST', body: { decisionNote: 'Chưa phù hợp' },
    }]);
    expect(mockedRequest.mock.calls[1]).toEqual(['/ai/workout-reschedules/rec-1/reject', { method: 'POST' }]);
  });

  it('keeps the same accept intent after a network failure and exposes replay receipt', async () => {
    const body: AcceptRescheduleApiRequest = {
      commandKey: 'stable-intent', expectedRevision: 1, expectedOccurrenceVersion: 4,
    };
    mockedRequest.mockRejectedValueOnce(new ApiError(0, 'Network failure'));
    mockedRequest.mockResolvedValueOnce({ recommendationId: 'rec-1', status: 'APPLIED', replayed: true });
    await expect(aiWorkoutRescheduleApi.accept('rec-1', body)).rejects.toMatchObject({ status: 0 });
    expect(mockedRequest).toHaveBeenCalledTimes(1);
    await expect(aiWorkoutRescheduleApi.accept('rec-1', body)).resolves.toMatchObject({ replayed: true });
    expect(mockedRequest.mock.calls[0][1].body).toEqual(mockedRequest.mock.calls[1][1].body);
  });

  it('requires an explicit nonblank key for each mutation intent', () => {
    expect(() => aiWorkoutRescheduleApi.generate({ occurrenceId: 'occ-1', commandKey: ' ' })).toThrow('commandKey');
    expect(() => aiWorkoutRescheduleApi.accept('rec-1', { commandKey: '' })).toThrow('commandKey');
    expect(mockedRequest).not.toHaveBeenCalled();
  });

  it.each([
    [400, 'VALIDATION'], [401, 'AUTHENTICATION'], [403, 'AUTHORITY'],
    [404, 'NOT_FOUND'], [409, 'CONFLICT'], [408, 'TIMEOUT'],
    [504, 'TIMEOUT'], [0, 'NETWORK'],
  ] as const)('classifies HTTP status %i as %s without hiding ApiError', (status, kind) => {
    const error = new ApiError(status, 'Server response', {
      errorCode: status === 400 ? 'VALIDATION_FAILED' : 'ERROR',
      message: 'Server response', timestamp: '', requestId: 'req-1',
      fieldErrors: status === 400 ? [{ field: 'occurrenceId', code: 'REQUIRED', message: 'Required' }] : [],
    });
    expect(getWorkoutRescheduleFailureKind(error)).toBe(kind);
    if (status === 400) expect(error.errorResponse?.fieldErrors[0].field).toBe('occurrenceId');
  });

  it('distinguishes a local cancellation from timeout', () => {
    expect(getWorkoutRescheduleFailureKind(Object.assign(new Error('Aborted'), { name: 'AbortError' }))).toBe('CANCELLED');
    expect(getWorkoutRescheduleFailureKind(new Error('Request timeout after 15000ms'))).toBe('TIMEOUT');
  });
});
