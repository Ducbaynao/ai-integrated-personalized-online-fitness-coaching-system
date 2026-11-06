import {
  aiProgressSummaryApi,
  getAiProgressSummaryErrorMessage,
  CONTINUITY_STATE_LABELS,
  METRIC_LABELS,
  METRIC_OPTIONS,
  TREND_DIRECTION_LABELS,
  getUserTimezone,
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
          metricCode: 'WEIGHT',
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
    missingData: ['BODY_FAT_PERCENTAGE'],
    factsReferenced: ['workout.completed_count', 'measurement.WEIGHT.latest'],
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
    it('sends POST request to /ai/progress/summary with resolved user timezone', async () => {
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
          timezone: getUserTimezone(),
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
          metricCodes: ['WEIGHT', 'BODY_FAT_PERCENTAGE'],
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
          metricCodes: ['WEIGHT', 'BODY_FAT_PERCENTAGE'],
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
    it('distinguishes cancellation from timeout on AbortError', () => {
      const abortError = new Error('The user aborted a request.');
      abortError.name = 'AbortError';
      expect(getAiProgressSummaryErrorMessage(abortError)).toBe('Yêu cầu đã bị hủy.');
    });

    it('returns timeout message on actual timeout error', () => {
      const timeoutError = new Error('Request timeout after 15000ms');
      expect(getAiProgressSummaryErrorMessage(timeoutError)).toContain('quá thời gian chờ (timeout)');
    });

    it('returns distinct messages for 502 Bad Gateway and 503 Service Unavailable', () => {
      const err502 = new ApiError(502, 'Bad Gateway');
      expect(getAiProgressSummaryErrorMessage(err502)).toContain('Bad Gateway');

      const err503 = new ApiError(503, 'Service Unavailable');
      expect(getAiProgressSummaryErrorMessage(err503)).toContain('Service Unavailable');
    });

    it('returns server timeout message on 504 Gateway Timeout', () => {
      const err = new ApiError(504, 'Gateway Timeout');
      expect(getAiProgressSummaryErrorMessage(err)).toContain('Gateway Timeout');
    });

    it('returns access denied message on 403 ApiError', () => {
      const err = new ApiError(403, 'Forbidden', {
        errorCode: 'AI_RUN_ACCESS_DENIED',
        message: 'No active coaching relationship',
        timestamp: '2026-11-06T10:00:00Z',
        requestId: 'req-1',
        fieldErrors: [],
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('không có quyền truy cập');
    });

    it('returns not found message on 404 ApiError', () => {
      const err = new ApiError(404, 'Not Found', {
        errorCode: 'STUDENT_NOT_FOUND',
        message: 'Student not found',
        timestamp: '2026-11-06T10:00:00Z',
        requestId: 'req-2',
        fieldErrors: [],
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('Không tìm thấy');
    });

    it('returns field errors when validation fails with 400', () => {
      const err = new ApiError(400, 'Bad Request', {
        errorCode: 'VALIDATION_FAILED',
        message: 'Validation failed',
        timestamp: '2026-11-06T10:00:00Z',
        requestId: 'req-3',
        fieldErrors: [{ field: 'fromDate', message: 'fromDate cannot be after toDate', code: 'INVALID' }],
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('fromDate cannot be after toDate');
    });

    it('returns contract validation failure message on 422', () => {
      const err = new ApiError(422, 'Unprocessable Entity', {
        errorCode: 'AI_OUTPUT_VALIDATION_FAILED',
        message: 'Contract violated',
        timestamp: '2026-11-06T10:00:00Z',
        requestId: 'req-4',
        fieldErrors: [],
      });
      expect(getAiProgressSummaryErrorMessage(err)).toContain('không vượt qua kiểm định an toàn');
    });

    it('returns network error message on status 0', () => {
      const err = new ApiError(0, 'Network request failed');
      expect(getAiProgressSummaryErrorMessage(err)).toContain('Mất kết nối mạng');
    });
  });

  describe('Constants and Labels', () => {
    it('defines expected metric options and continuity labels', () => {
      expect(METRIC_OPTIONS.some((m) => m.code === 'WEIGHT')).toBe(true);
      expect(METRIC_LABELS['WEIGHT']).toBe('Cân nặng (kg)');
      expect(METRIC_LABELS['F_WEIGHT']).toBe('Cân nặng (kg)');
      expect(CONTINUITY_STATE_LABELS['PROLONGED_INACTIVITY']).toContain('Gián đoạn');
      expect(TREND_DIRECTION_LABELS['STABLE'].symbol).toBe('→');
      expect(TREND_DIRECTION_LABELS['INCREASED'].symbol).toBe('↑');
    });
  });
});
