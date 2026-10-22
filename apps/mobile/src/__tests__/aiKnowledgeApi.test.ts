import {
  aiKnowledgeApi,
  AiKnowledgeValidationError,
  countCodePoints,
  getAiKnowledgeErrorMessage,
  F01_QUESTION_MIN_LENGTH,
  F01_QUESTION_MAX_LENGTH,
} from '@/services/aiKnowledgeApi';
import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import { F01KnowledgeQueryResponse } from '@/types/aiKnowledge';

jest.mock('@/services/apiClient', () => ({
  request: jest.fn(),
}));

describe('aiKnowledgeApi', () => {
  const mockResponse: F01KnowledgeQueryResponse = {
    runId: '11111111-1111-1111-1111-111111111111',
    correlationId: '22222222-2222-2222-2222-222222222222',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    answer: 'WHO khuyến nghị ít nhất 150-300 phút hoạt động thể chất vừa phải mỗi tuần.',
    citations: [
      {
        documentId: 'WHO-PA-SB-2020-VI-01',
        version: '1.0.0',
        sourceId: 'WHO-PA-SB-2020',
        locator: {
          sourcePath: 'who-pa-sb-2020/01-adults.vi.md',
          heading: 'Khuyến nghị cho người lớn',
          pageRange: [15, 20],
        },
        quoteOrExcerpt: 'Ít nhất 150-300 phút hoạt động thể lực cường độ vừa',
      },
    ],
    limitations: ['Người trưởng thành khỏe mạnh'],
    missingData: [],
    asOf: '2026-10-22T10:00:00Z',
    usage: {
      promptTokens: 100,
      completionTokens: 50,
      totalTokens: 150,
      latencyMs: 1200,
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('countCodePoints', () => {
    it('accurately counts Unicode code points and accented characters', () => {
      expect(countCodePoints('Tập tạ')).toBe(6);
      expect(countCodePoints('Khuyến nghị thể hình')).toBe(20);
      expect(countCodePoints('🏋️‍♂️')).toBe(5); // 5 unicode code points (weight lifter + VS16 + ZWJ + male + VS16)
    });
  });

  describe('queryKnowledge validation', () => {
    it('throws AiKnowledgeValidationError when question is empty', async () => {
      await expect(aiKnowledgeApi.queryKnowledge({ question: '' })).rejects.toThrow(
        AiKnowledgeValidationError
      );
      await expect(aiKnowledgeApi.queryKnowledge({ question: '   ' })).rejects.toThrow(
        `Câu hỏi phải có ít nhất ${F01_QUESTION_MIN_LENGTH} ký tự.`
      );
    });

    it('throws AiKnowledgeValidationError when question is shorter than min length', async () => {
      await expect(aiKnowledgeApi.queryKnowledge({ question: 'Tập?' })).rejects.toThrow(
        `Câu hỏi phải có ít nhất ${F01_QUESTION_MIN_LENGTH} ký tự.`
      );
      expect(request).not.toHaveBeenCalled();
    });

    it('throws AiKnowledgeValidationError when question exceeds max length', async () => {
      const longQuestion = 'a'.repeat(F01_QUESTION_MAX_LENGTH + 1);
      await expect(aiKnowledgeApi.queryKnowledge({ question: longQuestion })).rejects.toThrow(
        `Câu hỏi không được vượt quá ${F01_QUESTION_MAX_LENGTH} ký tự.`
      );
      expect(request).not.toHaveBeenCalled();
    });

    it('accepts question at exact minimum boundary (5 code points)', async () => {
      (request as jest.Mock).mockResolvedValueOnce(mockResponse);
      await expect(aiKnowledgeApi.queryKnowledge({ question: '12345' })).resolves.toEqual(mockResponse);
      expect(request).toHaveBeenCalledWith('/ai/knowledge/query', expect.objectContaining({
        body: expect.objectContaining({ question: '12345' }),
      }));
    });

    it('accepts question at exact maximum boundary (1000 code points)', async () => {
      (request as jest.Mock).mockResolvedValueOnce(mockResponse);
      const exactMaxQuestion = 'x'.repeat(F01_QUESTION_MAX_LENGTH);
      await expect(aiKnowledgeApi.queryKnowledge({ question: exactMaxQuestion })).resolves.toEqual(mockResponse);
      expect(request).toHaveBeenCalledWith('/ai/knowledge/query', expect.objectContaining({
        body: expect.objectContaining({ question: exactMaxQuestion }),
      }));
    });

    it('handles unicode and emojis accurately at boundary', async () => {
      (request as jest.Mock).mockResolvedValueOnce(mockResponse);
      // 5 code points: '🏃' (1) + ' ' (1) + 't' (1) + 'ạ' (1) + '!' (1) = 5 code points
      const unicodeMin = '🏃 tạ!';
      expect(countCodePoints(unicodeMin)).toBe(5);
      await expect(aiKnowledgeApi.queryKnowledge({ question: unicodeMin })).resolves.toEqual(mockResponse);
    });
  });

  describe('queryKnowledge network call', () => {
    it('calls Spring Boot endpoint with trimmed question, default language vi, and abort signal', async () => {
      (request as jest.Mock).mockResolvedValueOnce(mockResponse);

      const result = await aiKnowledgeApi.queryKnowledge({
        question: '  Khuyến nghị tập luyện của WHO là gì?  ',
      });

      expect(request).toHaveBeenCalledTimes(1);
      expect(request).toHaveBeenCalledWith('/ai/knowledge/query', {
        method: 'POST',
        body: {
          question: 'Khuyến nghị tập luyện của WHO là gì?',
          language: 'vi',
        },
        signal: expect.any(Object),
      });
      expect(result).toEqual(mockResponse);
    });

    it('preserves optional exerciseId and correlationId in payload', async () => {
      (request as jest.Mock).mockResolvedValueOnce(mockResponse);

      await aiKnowledgeApi.queryKnowledge({
        question: 'Kỹ thuật Squat đúng cách',
        language: 'en',
        exerciseId: '33333333-3333-3333-3333-333333333333',
        correlationId: '44444444-4444-4444-4444-444444444444',
      });

      expect(request).toHaveBeenCalledWith('/ai/knowledge/query', {
        method: 'POST',
        body: {
          question: 'Kỹ thuật Squat đúng cách',
          language: 'en',
          exerciseId: '33333333-3333-3333-3333-333333333333',
          correlationId: '44444444-4444-4444-4444-444444444444',
        },
        signal: expect.any(Object),
      });
    });
  });

  describe('getAiKnowledgeErrorMessage', () => {
    it('handles AiKnowledgeValidationError', () => {
      const err = new AiKnowledgeValidationError('Lỗi độ dài câu hỏi');
      expect(getAiKnowledgeErrorMessage(err)).toBe('Lỗi độ dài câu hỏi');
    });

    it('handles AbortError as timeout', () => {
      const err = new Error('The operation was aborted');
      err.name = 'AbortError';
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Yêu cầu tra cứu quá thời gian chờ (timeout). Vui lòng thử lại sau.'
      );
    });

    it('distinguishes reasonCode VALIDATION_FAILED on HTTP 502 instead of collapsing to model error', () => {
      const err = new ApiError(502, 'Bad Gateway', undefined, {
        runStatus: 'REJECTED_BY_VALIDATOR',
        reasonCode: 'VALIDATION_FAILED',
        error: { code: 'UNVERIFIED_CITATION', message: 'Citation failed verification' },
      });
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Kết quả từ dịch vụ AI không vượt qua kiểm định an toàn và tính chính xác trích dẫn.'
      );
    });

    it('distinguishes reasonCode MODEL_ERROR on HTTP 502', () => {
      const err = new ApiError(502, 'Bad Gateway', undefined, {
        runStatus: 'FAILED',
        reasonCode: 'MODEL_ERROR',
        error: { code: 'MODEL_ERROR', message: 'OpenAI returned 500' },
      });
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Dịch vụ mô hình AI gặp sự cố trong quá trình tạo câu trả lời. Vui lòng thử lại sau.'
      );
    });

    it('distinguishes reasonCode TIMEOUT on HTTP 504', () => {
      const err = new ApiError(504, 'Gateway Timeout', undefined, {
        runStatus: 'FAILED',
        reasonCode: 'TIMEOUT',
      });
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Yêu cầu tra cứu quá thời gian chờ (timeout). Vui lòng thử lại sau.'
      );
    });

    it('handles ApiError 401 session expiration', () => {
      const err = new ApiError(401, 'Unauthorized');
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
      );
    });

    it('handles ApiError 403 forbidden', () => {
      const err = new ApiError(403, 'Forbidden');
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Bạn không có quyền truy cập tính năng tra cứu kiến thức AI.'
      );
    });

    it('handles ApiError 0 network failure', () => {
      const err = new ApiError(0, 'Network Error');
      expect(getAiKnowledgeErrorMessage(err)).toBe(
        'Mất kết nối mạng. Vui lòng kiểm tra đường truyền và thử lại.'
      );
    });

    it('handles generic Error', () => {
      const err = new Error('Lỗi tùy biến');
      expect(getAiKnowledgeErrorMessage(err)).toBe('Lỗi tùy biến');
    });
  });
});
