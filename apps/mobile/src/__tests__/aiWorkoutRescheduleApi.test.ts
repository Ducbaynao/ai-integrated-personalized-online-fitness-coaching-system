import {
  aiWorkoutRescheduleApi,
  getWorkoutRescheduleErrorMessage,
  getWorkoutRescheduleFieldErrors,
} from '@/services/aiWorkoutRescheduleApi';
import { request } from '@/services/apiClient';
import { ApiError, ErrorResponse } from '@/types/auth';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

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
