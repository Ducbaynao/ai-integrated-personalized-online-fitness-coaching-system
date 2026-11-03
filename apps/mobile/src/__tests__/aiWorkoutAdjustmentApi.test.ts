import {
  aiWorkoutAdjustmentApi,
  getWorkoutAdjustmentErrorMessage,
  getWorkoutAdjustmentFieldErrors,
} from '@/services/aiWorkoutAdjustmentApi';
import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('aiWorkoutAdjustmentApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('generate calls POST /ai/workout-adjustments with payload', async () => {
    const mockResponse = {
      recommendationId: 'rec-123',
      runId: 'run-456',
      status: 'PENDING',
      decisionAuthority: 'STUDENT',
      title: 'Đề xuất thay bài tập',
      explanation: 'Thay thế bài Squat',
      expiresAt: '2026-11-04T00:00:00Z',
      createdAt: '2026-11-03T00:00:00Z',
      structuredPayload: {
        recommendationType: 'WORKOUT_ADJUSTMENT',
        summary: 'Tóm tắt',
        reasoning: [],
        usedData: [],
        missingData: [],
        limitations: [],
        confidence: 0.9,
        changes: [],
      },
    };
    (request as jest.Mock).mockResolvedValueOnce(mockResponse);

    const result = await aiWorkoutAdjustmentApi.generate({
      studentId: 'stu-1',
      commandKey: 'cmd-adj-1',
      scope: 'ONE_MATERIALIZED_OCCURRENCE',
      target: {
        scope: 'ONE_MATERIALIZED_OCCURRENCE',
        plannedWorkoutId: 'occ-1',
        plannedSessionExerciseId: 'ex-1',
      },
      requestedChangeType: 'EXERCISE_SUBSTITUTION',
      userReason: 'Máy bận',
    });

    expect(request).toHaveBeenCalledWith('/ai/workout-adjustments', {
      method: 'POST',
      body: expect.objectContaining({
        studentId: 'stu-1',
        commandKey: 'cmd-adj-1',
        scope: 'ONE_MATERIALIZED_OCCURRENCE',
        requestedChangeType: 'EXERCISE_SUBSTITUTION',
      }),
    });
    expect(result).toEqual(mockResponse);
  });

  it('getRecommendation calls GET /ai/workout-adjustments/{id}', async () => {
    const mockDetail = { id: 'rec-123', title: 'Chi tiết' };
    (request as jest.Mock).mockResolvedValueOnce(mockDetail);

    const result = await aiWorkoutAdjustmentApi.getRecommendation('rec-123');
    expect(request).toHaveBeenCalledWith('/ai/workout-adjustments/rec-123', {
      method: 'GET',
    });
    expect(result).toEqual(mockDetail);
  });

  it('accept calls POST /ai/workout-adjustments/{id}/accept with body', async () => {
    const mockAccept = {
      recommendationId: 'rec-123',
      status: 'APPLIED',
      resultingEntityType: 'WORKOUT_OCCURRENCE',
      resultingEntityId: 'occ-1',
      appliedAt: '2026-11-03T01:00:00Z',
      replayed: false,
    };
    (request as jest.Mock).mockResolvedValueOnce(mockAccept);

    const result = await aiWorkoutAdjustmentApi.accept('rec-123', {
      commandKey: 'accept-cmd-1',
      expectedTargetVersion: 2,
      expectedRevision: 1,
      decisionNote: 'Đồng ý thay bài',
    });

    expect(request).toHaveBeenCalledWith('/ai/workout-adjustments/rec-123/accept', {
      method: 'POST',
      body: {
        commandKey: 'accept-cmd-1',
        expectedTargetVersion: 2,
        expectedRevision: 1,
        decisionNote: 'Đồng ý thay bài',
      },
    });
    expect(result).toEqual(mockAccept);
  });

  it('reject calls POST /ai/workout-adjustments/{id}/reject with reason', async () => {
    const mockReject = {
      recommendationId: 'rec-123',
      status: 'REJECTED',
      rejectedBy: 'stu-1',
      reason: 'Không phù hợp',
      rejectedAt: '2026-11-03T01:00:00Z',
    };
    (request as jest.Mock).mockResolvedValueOnce(mockReject);

    const result = await aiWorkoutAdjustmentApi.reject('rec-123', {
      reason: 'Không phù hợp',
    });

    expect(request).toHaveBeenCalledWith('/ai/workout-adjustments/rec-123/reject', {
      method: 'POST',
      body: { reason: 'Không phù hợp' },
    });
    expect(result).toEqual(mockReject);
  });

  describe('getWorkoutAdjustmentErrorMessage', () => {
    const makeError = (status: number, message: string, errorCode?: string) => {
      return new ApiError(status, message, {
        errorCode: errorCode || 'ERROR',
        message,
        timestamp: '2026-11-03T00:00:00Z',
        requestId: 'req-1',
        fieldErrors: [],
      });
    };

    it('maps PLANNED_WORKOUT_ADJUSTMENTS_SEALED', () => {
      const err = makeError(409, 'sealed', 'PLANNED_WORKOUT_ADJUSTMENTS_SEALED');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Buổi tập đã bắt đầu hoặc hoàn thành');
    });

    it('maps PLANNED_WORKOUT_VERSION_CONFLICT', () => {
      const err = makeError(409, 'conflict', 'PLANNED_WORKOUT_VERSION_CONFLICT');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Phiên bản buổi tập đã bị thay đổi');
    });

    it('maps WORKOUT_PLAN_VERSION_CONFLICT', () => {
      const err = makeError(409, 'conflict', 'WORKOUT_PLAN_VERSION_CONFLICT');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Phiên bản kế hoạch đã bị thay đổi');
    });

    it('maps WORKOUT_PLAN_EXERCISE_UNAVAILABLE', () => {
      const err = makeError(409, 'unavailable', 'WORKOUT_PLAN_EXERCISE_UNAVAILABLE');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Biến thể bài tập thay thế hiện không khả dụng');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for expired proposal', () => {
      const err = makeError(409, 'Recommendation has expired and cannot be accepted.', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('hết hạn');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for only SCHEDULED', () => {
      const err = makeError(409, 'only SCHEDULED occurrences can be adjusted', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('trạng thái ĐÃ LÊN LỊCH (SCHEDULED)');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for Idempotency conflict', () => {
      const err = makeError(409, 'Idempotency conflict detected', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Xung đột thao tác');
    });

    it('maps 403 / AI_RUN_ACCESS_DENIED to authority explanation', () => {
      const err = makeError(403, 'Access denied', 'AI_RUN_ACCESS_DENIED');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('không có thẩm quyền quyết định');
    });

    it('maps 404 to not found explanation', () => {
      const err = new ApiError(404, 'Not found');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Không tìm thấy đề xuất điều chỉnh');
    });

    it('maps status 0 to network failure', () => {
      const err = new ApiError(0, 'Network error');
      expect(getWorkoutAdjustmentErrorMessage(err)).toContain('Mất kết nối mạng');
    });
  });

  describe('getWorkoutAdjustmentFieldErrors', () => {
    it('returns empty object when no field errors exist', () => {
      expect(getWorkoutAdjustmentFieldErrors(new Error('general'))).toEqual({});
    });

    it('maps field errors from ApiError', () => {
      const err = new ApiError(400, 'Validation error', {
        errorCode: 'VALIDATION_FAILED',
        message: 'Validation failed',
        timestamp: '',
        requestId: '',
        fieldErrors: [
          { field: 'userReason', code: 'REQUIRED', message: 'Lý do bắt buộc' },
          { field: 'target', code: 'INVALID', message: 'Mục tiêu không hợp lệ' },
        ],
      });
      expect(getWorkoutAdjustmentFieldErrors(err)).toEqual({
        userReason: 'Lý do bắt buộc',
        target: 'Mục tiêu không hợp lệ',
      });
    });
  });
});
