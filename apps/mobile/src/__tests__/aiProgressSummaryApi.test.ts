import {
  aiProgressSummaryApi,
  getAiProgressSummaryErrorMessage,
  CONTINUITY_STATE_LABELS,
  METRIC_OPTIONS,
  TREND_DIRECTION_LABELS,
  F04_DEFAULT_TIMEZONE,
} from '@/services/aiProgressSummaryApi';
import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import { F04ProgressSummaryResponse } from '@/types/aiProgressSummary';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('aiProgressSummaryApi', () => {
  const sampleSuccessResponse: F04ProgressSummaryResponse = {
    runId: '11111111-1111-1111-1111-111111111111',
    correlationId: '22222222-2222-2222-2222-222222222222',
    studentId: '33333333-3333-3333-3333-333333333333',
    actorId: '33333333-3333-3333-3333-333333333333',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    summary: 'Trong 14 ngày qua, bạn hoàn thành 4 buổi tập với độ tuân thủ 100%.',
    workoutProgressSummary: {
      completionEvaluation: '4/4 buổi tập đã hoàn thành đúng hạn.',
      adherenceEvaluation: 'Tuân thủ lịch tập 100%.',
      volumeEvaluation: 'Tổng khối lượng tập đạt 12,500 kg.',
    },
    measurementProgressSummary: {
      metricsEvaluations: [
        {
          metricCode: 'F_WEIGHT',
          trendDirection: 'STABLE',
          message: 'Cân nặng duy trì ổn định ở mức 70.0 kg.',
        },
      ],
    },
    continuityEvaluation: {
      state: 'RECENT_ACTIVITY_OBSERVED',
      message: 'Ghi nhận buổi tập gần nhất 2 ngày trước.',
      hasSafetyAlert: false,
    },
    limitations: ['Chưa có chỉ số % mỡ chi tiết'],
    missingData: ['F_BODY_FAT'],
    factsReferenced: ['workout.completed_count', 'measurement.F_WEIGHT.latest'],
    asOf: '2026-11-06T10:00:00Z',
    sourceWatermark: 'watermark-12345',
    usage: {
      promptTokens: 120,
      completionTokens: 85,
      totalTokens: 205,
    },
    isStale: false,
    needsRefresh: false,
    currentWatermark: 'watermark-12345',
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('generate', () => {
    it('sends POST request to /ai/progress/summary with defaults', async () => {
      (request as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

      const result = await aiProgressSummaryApi.generate({
        studentId: '33333333-3333-3333-3333-333333333333',
        fromDate: '2026-10-23',
        toDate: '2026-11-06',
      });

      expect(request).toHaveBeenCalledTimes(1);
      expect(request).toHaveBeenCalledWith('/ai/progress/summary', {
        method: 'POST',
        body: {
          studentId: '33333333-3333-3333-3333-333333333333',
          fromDate: '2026-10-23',
          toDate: '2026-11-06',
          timezone: F04_DEFAULT_TIMEZONE,
        },
        signal: undefined,
      });
      expect(result).toEqual(sampleSuccessResponse);
    });

    it('passes custom timezone, metrics, and abort signal', async () => {
      (request as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);
      const controller = new AbortController();

      await aiProgressSummaryApi.generate(
        {
          studentId: '33333333-3333-3333-3333-333333333333',
          fromDate: '2026-10-01',
          toDate: '2026-10-31',
          timezone: 'Asia/Bangkok',
          metricCodes: ['F_WEIGHT', 'F_BODY_FAT'],
        },
        controller.signal
      );

      expect(request).toHaveBeenCalledWith('/ai/progress/summary', {
        method: 'POST',
        body: {
          studentId: '33333333-3333-3333-3333-333333333333',
          fromDate: '2026-10-01',
          toDate: '2026-10-31',
          timezone: 'Asia/Bangkok',
          metricCodes: ['F_WEIGHT', 'F_BODY_FAT'],
        },
        signal: controller.signal,
      });
    });
  });

  describe('getSummary', () => {
    it('sends GET request to /ai/progress/summary/{runId}', async () => {
      (request as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

      const result = await aiProgressSummaryApi.getSummary('11111111-1111-1111-1111-111111111111');

      expect(request).toHaveBeenCalledTimes(1);
      expect(request).toHaveBeenCalledWith(
        '/ai/progress/summary/11111111-1111-1111-1111-111111111111',
        {
          method: 'GET',
          signal: undefined,
        }
      );
      expect(result).toEqual(sampleSuccessResponse);
    });
  });

  describe('getAiProgressSummaryErrorMessage', () => {
    it('returns timeout message on AbortError', () => {
      const abortError = new Error('The user aborted a request.');
      abortError.name = 'AbortError';
      expect(getAiProgressSummaryErrorMessage(abortError)).toContain('quá thời gian chờ');
    });

    it('returns access denied message on 403 ApiError', () => {
      const err = new ApiError(403, 'Forbidden', {
        errorCode: 'AI_RUN_ACCESS_DENIED',
        message: 'No active coaching relationship',
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('không có quyền truy cập');
    });

    it('returns not found message on 404 ApiError', () => {
      const err = new ApiError(404, 'Not Found', {
        errorCode: 'STUDENT_NOT_FOUND',
        message: 'Student not found',
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('Không tìm thấy');
    });

    it('returns field errors when validation fails with 400', () => {
      const err = new ApiError(400, 'Bad Request', {
        errorCode: 'VALIDATION_FAILED',
        message: 'Validation failed',
        fieldErrors: [{ field: 'fromDate', message: 'fromDate cannot be after toDate' }],
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('fromDate cannot be after toDate');
    });

    it('returns contract validation failure message on 422', () => {
      const err = new ApiError(422, 'Unprocessable Entity', {
        errorCode: 'AI_OUTPUT_VALIDATION_FAILED',
        message: 'Contract violated',
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('không vượt qua kiểm định an toàn');
    });

    it('returns server timeout message on 504 Gateway Timeout', () => {
      const err = new ApiError(504, 'Gateway Timeout');
      expect(getAiProgressSummaryErrorMessage(err)).toContain('tạm thời không khả dụng hoặc quá thời gian chờ');
    });

    it('returns network error message on status 0', () => {
      const err = new ApiError(0, 'Network request failed');
      expect(getAiProgressSummaryErrorMessage(err)).toContain('Mất kết nối mạng');
    });
  });

  describe('Constants and Labels', () => {
    it('defines expected metric options and continuity labels', () => {
      expect(METRIC_OPTIONS.some((m) => m.code === 'F_WEIGHT')).toBe(true);
      expect(CONTINUITY_STATE_LABELS['PROLONGED_INACTIVITY']).toContain('Gián đoạn');
      expect(TREND_DIRECTION_LABELS['STABLE'].symbol).toBe('→');
      expect(TREND_DIRECTION_LABELS['INCREASED'].symbol).toBe('↑');
    });
  });
});
