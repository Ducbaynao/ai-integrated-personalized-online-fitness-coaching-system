import {
  aiWorkoutProposalApi,
  getWorkoutProposalErrorMessage,
} from '@/services/aiWorkoutProposalApi';
import { request } from '@/services/apiClient';
import { ApiError, ErrorResponse } from '@/types/auth';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('aiWorkoutProposalApi', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('generate calls POST /ai/workout-proposals with commandKey and preferences', async () => {
    const mockRes = { recommendationId: 'rec-1', status: 'PENDING' };
    (request as jest.Mock).mockResolvedValueOnce(mockRes);

    const result = await aiWorkoutProposalApi.generate({
      studentId: 'student-1',
      preferences: { daysPerWeek: 3 },
    }, 'cmd-key-1');

    expect(request).toHaveBeenCalledWith('/ai/workout-proposals', {
      method: 'POST',
      body: {
        studentId: 'student-1',
        preferences: { daysPerWeek: 3 },
        commandKey: 'cmd-key-1',
      },
    });
    expect(result).toBe(mockRes);
  });

  it('getRecommendation calls GET /ai/recommendations/{id}', async () => {
    const mockRes = { recommendationId: 'rec-123', status: 'PENDING' };
    (request as jest.Mock).mockResolvedValueOnce(mockRes);

    const result = await aiWorkoutProposalApi.getRecommendation('rec-123');
    expect(request).toHaveBeenCalledWith('/ai/recommendations/rec-123', {
      method: 'GET',
    });
    expect(result).toBe(mockRes);
  });

  it('accept calls POST /ai/recommendations/{id}/accept', async () => {
    const mockRes = { recommendationId: 'rec-123', status: 'APPLIED', replayed: false };
    (request as jest.Mock).mockResolvedValueOnce(mockRes);

    const result = await aiWorkoutProposalApi.accept('rec-123', 'Looks great', 'accept-cmd-1');
    expect(request).toHaveBeenCalledWith('/ai/recommendations/rec-123/accept', {
      method: 'POST',
      body: {
        commandKey: 'accept-cmd-1',
        decisionNote: 'Looks great',
      },
    });
    expect(result).toBe(mockRes);
  });

  it('reject calls POST /ai/recommendations/{id}/reject', async () => {
    const mockRes = { recommendationId: 'rec-123', status: 'REJECTED' };
    (request as jest.Mock).mockResolvedValueOnce(mockRes);

    const result = await aiWorkoutProposalApi.reject('rec-123', 'Too difficult');
    expect(request).toHaveBeenCalledWith('/ai/recommendations/rec-123/reject', {
      method: 'POST',
      body: { reason: 'Too difficult' },
    });
    expect(result).toBe(mockRes);
  });

  describe('getWorkoutProposalErrorMessage', () => {
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

    it('maps ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS to clear instructions', () => {
      const err = makeError(409, 'Conflict', 'ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS');
      expect(getWorkoutProposalErrorMessage(err)).toContain('đang áp dụng');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for expired proposals', () => {
      const err = makeError(409, 'Recommendation has expired', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutProposalErrorMessage(err)).toContain('hết hạn');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for already applied proposals', () => {
      const err = makeError(409, 'Recommendation has already been applied', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutProposalErrorMessage(err)).toContain('đã được áp dụng');
    });

    it('maps AI_RUN_LIFECYCLE_CONFLICT for idempotency conflicts', () => {
      const err = makeError(409, 'Idempotency conflict detected', 'AI_RUN_LIFECYCLE_CONFLICT');
      expect(getWorkoutProposalErrorMessage(err)).toContain('Xung đột thao tác');
    });

    it('maps 403 to authority explanation', () => {
      const err = makeError(403, 'Forbidden', 'AI_FORBIDDEN');
      expect(getWorkoutProposalErrorMessage(err)).toContain('thẩm quyền');
    });

    it('maps 404 to not found explanation', () => {
      const err = makeError(404, 'Not Found', 'AI_RUN_NOT_FOUND');
      expect(getWorkoutProposalErrorMessage(err)).toContain('Không tìm thấy');
    });

    it('maps status 0 to network failure', () => {
      const err = new ApiError(0, 'Failed to fetch');
      expect(getWorkoutProposalErrorMessage(err)).toContain('Mất kết nối');
    });
  });
});
