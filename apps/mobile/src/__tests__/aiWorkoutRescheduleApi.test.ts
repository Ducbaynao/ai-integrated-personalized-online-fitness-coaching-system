import { aiWorkoutRescheduleApi, getWorkoutRescheduleFailureKind, getWorkoutRescheduleErrorMessage, getWorkoutRescheduleFieldErrors } from '@/services/aiWorkoutRescheduleApi';
import { request } from '@/services/apiClient';
import { ApiError, ErrorResponse } from '@/types/auth';
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

describe('aiWorkoutRescheduleApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('generate calls POST /ai/workout-reschedules with commandKey and inputs', async () => {
    const mockRes = {
      recommendationId: 'rec-1',
      runId: 'run-1',
      status: 'PENDING',
      decisionAuthority: 'STUDENT',
      title: 'Dời lịch buổi tập',
      explanation: 'Dời sang 09:00 ngày mai',
      expiresAt: '2026-11-23T12:00:00Z',
      createdAt: '2026-11-23T11:30:00Z',
      structuredPayload: {},
    };
    (request as jest.Mock).mockResolvedValueOnce(mockRes);

    const result = await aiWorkoutRescheduleApi.generate(
      {
        occurrenceId: 'occ-1',
        studentId: 'student-1',
        expectedOccurrenceVersion: 1,
        reason: 'Bận đột xuất',
        preferredTimeOfDay: 'MORNING',
      },
      'cmd-key-1'
    );

    expect(request).toHaveBeenCalledWith('/ai/workout-reschedules', {
      method: 'POST',
      body: {
        occurrenceId: 'occ-1',
        studentId: 'student-1',
        expectedOccurrenceVersion: 1,
        reason: 'Bận đột xuất',
        preferredTimeOfDay: 'MORNING',
        commandKey: 'cmd-key-1',
      },
    });
    expect(result).toBe(mockRes);
  });

  it('getDetail calls GET /ai/workout-reschedules/{recommendationId}', async () => {
    const mockDetail = {
      recommendationId: 'rec-123',
      status: 'PENDING',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockDetail);

    const result = await aiWorkoutRescheduleApi.getDetail('rec-123');
    expect(request).toHaveBeenCalledWith('/ai/workout-reschedules/rec-123', {
      method: 'GET',
    });
    expect(result).toBe(mockDetail);
  });

  it('accept calls POST /ai/workout-reschedules/{id}/accept with retry and appointment identity', async () => {
    const mockAcceptRes = {
      recommendationId: 'rec-123',
      status: 'APPLIED',
      resultingEntityType: 'WORKOUT_OCCURRENCE',
      resultingEntityId: 'occ-2',
      replayed: false,
    };
    (request as jest.Mock).mockResolvedValueOnce(mockAcceptRes);

    const result = await aiWorkoutRescheduleApi.accept(
      'rec-123',
      {
        expectedRevision: 1,
        expectedOccurrenceVersion: 2,
        expectedAppointmentVersion: 3,
        expectedAppointmentId: 'apt-1',
        decisionNote: 'Đồng ý dời lịch',
      },
      'accept-key-1'
    );

    expect(request).toHaveBeenCalledWith('/ai/workout-reschedules/rec-123/accept', {
      method: 'POST',
      body: {
        commandKey: 'accept-key-1',
        expectedRevision: 1,
        expectedOccurrenceVersion: 2,
        expectedAppointmentVersion: 3,
        expectedAppointmentId: 'apt-1',
        decisionNote: 'Đồng ý dời lịch',
      },
    });
    expect(result).toBe(mockAcceptRes);
  });

  it('reject calls POST /ai/workout-reschedules/{id}/reject', async () => {
    const mockRejectRes = {
      recommendationId: 'rec-123',
      status: 'REJECTED',
      rejectedAt: '2026-11-23T11:45:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockRejectRes);

    const result = await aiWorkoutRescheduleApi.reject('rec-123', {
      decisionNote: 'Không phù hợp giờ này',
    });

    expect(request).toHaveBeenCalledWith('/ai/workout-reschedules/rec-123/reject', {
      method: 'POST',
      body: {
        decisionNote: 'Không phù hợp giờ này',
      },
    });
    expect(result).toBe(mockRejectRes);
  });

  describe('getWorkoutRescheduleErrorMessage', () => {
    const makeError = (status: number, message: string, errorCode: string) => {
      const errResp: ErrorResponse = {
        errorCode,
        message,
        timestamp: '',
        requestId: '',
        fieldErrors: [],
      };
      return new ApiError(status, message, errResp);
    };

    it('maps WORKOUT_SCHEDULE_OVERLAP to clear slot conflict message', () => {
      const err = makeError(409, 'Slot overlap', 'WORKOUT_SCHEDULE_OVERLAP');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('trùng lặp');
    });

    it('maps PLANNED_WORKOUT_VERSION_CONFLICT to refresh guidance', () => {
      const err = makeError(409, 'Version conflict', 'PLANNED_WORKOUT_VERSION_CONFLICT');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('Phiên bản buổi tập');
    });

    it('maps APPOINTMENT_VERSION_CONFLICT to appointment drift message', () => {
      const err = makeError(409, 'Appointment conflict', 'APPOINTMENT_VERSION_CONFLICT');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('Phiên bản lịch hẹn liên kết');
    });

    it('maps WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED to supervision guidance', () => {
      const err = makeError(409, 'Coach required without appointment', 'WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('bắt buộc có huấn luyện viên');
    });

    it('maps APPOINTMENT_COACH_REQUIRED_DEPENDENCY to coordination guidance', () => {
      const err = makeError(409, 'Disjoint appointment move blocked', 'APPOINTMENT_COACH_REQUIRED_DEPENDENCY');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('phối hợp với huấn luyện viên');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for expired proposal', () => {
      const err = makeError(409, 'Recommendation expired after 30 minutes', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('hết hạn (quá 30 phút)');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for already applied proposal', () => {
      const err = makeError(409, 'Recommendation has already been applied', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('đã được áp dụng trước đó');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for idempotency conflict', () => {
      const err = makeError(409, 'Idempotency conflict: differing parameters', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('Xung đột thao tác (trùng khóa lệnh');
    });

    it('maps 403 HUMAN_COACH authority error', () => {
      const err = makeError(403, 'In HUMAN_COACH mode, only trainer holds decision authority', 'AI_RUN_ACCESS_DENIED');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('chỉ huấn luyện viên mới có quyền');
    });

    it('maps 404 to not found explanation', () => {
      const err = makeError(404, 'Not found', 'NOT_FOUND');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('Không tìm thấy đề xuất');
    });

    it('maps 422 to validation error', () => {
      const err = makeError(422, 'Output validation failed', 'AI_OUTPUT_VALIDATION_FAILED');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('không vượt qua kiểm định an toàn');
    });

    it('maps status 0 to network failure', () => {
      const err = new ApiError(0, 'Network request failed');
      expect(getWorkoutRescheduleErrorMessage(err)).toContain('Mất kết nối mạng');
    });
  });

  describe('getWorkoutRescheduleFieldErrors', () => {
    it('returns empty object when no field errors', () => {
      expect(getWorkoutRescheduleFieldErrors(new Error('general'))).toEqual({});
    });

    it('maps field errors with fallback', () => {
      const errResp: ErrorResponse = {
        errorCode: 'VALIDATION_FAILED',
        message: 'Invalid request',
        timestamp: '',
        requestId: '',
        fieldErrors: [
          { field: 'reason', message: 'Lý do quá dài', code: 'FIELD_TOO_LONG' },
          { field: 'preferredTimeOfDay', message: 'invalid time', code: 'INVALID_ENUM' },
        ],
      };
      const apiErr = new ApiError(400, 'Invalid', errResp);
      const errors = getWorkoutRescheduleFieldErrors(apiErr);
      expect(errors.reason).toBe('Lý do quá dài');
      expect(errors.preferredTimeOfDay).toBe('Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.');
    });
  });
});
