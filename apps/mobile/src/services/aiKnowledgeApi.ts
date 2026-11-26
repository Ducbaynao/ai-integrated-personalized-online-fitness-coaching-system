import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import {
  AiRunResponse,
  F01KnowledgeQueryRequest,
  F01KnowledgeQueryResponse,
} from '@/types/aiKnowledge';

export const F01_QUESTION_MIN_LENGTH = 5;
export const F01_QUESTION_MAX_LENGTH = 1000;
export const F01_DEFAULT_TIMEOUT_MS = 30000;

export class AiKnowledgeValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AiKnowledgeValidationError';
    Object.setPrototypeOf(this, AiKnowledgeValidationError.prototype);
  }
}

/**
 * Counts Unicode code points in a string, matching backend `String.codePointCount`.
 */
export function countCodePoints(str: string): number {
  return [...str].length;
}

/**
 * Normalizes user-facing error message based on ApiError, raw data, status, or exception type.
 * Aligned with docs/08-ai/f01-personalized-knowledge-mobile-handover.md Section 5.
 */
export function getAiKnowledgeErrorMessage(error: unknown): string {
  if (error instanceof AiKnowledgeValidationError) {
    return error.message;
  }

  // Handle AbortError / Timeout from client-side AbortController
  if (
    error instanceof Error &&
    (error.name === 'AbortError' || error.message.includes('aborted') || error.message.includes('timeout'))
  ) {
    if (error.message.includes('bị hủy')) {
      return error.message;
    }
    return 'Yêu cầu tra cứu quá thời gian chờ. Vui lòng thử lại sau.';
  }

  if (error instanceof ApiError) {
    // 1. Inspect structured rawData from F01KnowledgeQueryResponse if present
    if (error.rawData && typeof error.rawData === 'object') {
      const raw = error.rawData as Record<string, unknown>;
      const reasonCode = typeof raw.reasonCode === 'string' ? raw.reasonCode : null;
      const errorObj = raw.error && typeof raw.error === 'object' ? (raw.error as Record<string, unknown>) : null;
      const rawErrorMsg = errorObj && typeof errorObj.message === 'string' ? errorObj.message : null;

      if (reasonCode === 'VALIDATION_FAILED') {
        return 'Kết quả từ dịch vụ AI không vượt qua kiểm định an toàn và tính chính xác trích dẫn.';
      }
      if (reasonCode === 'MODEL_ERROR' || reasonCode === 'PROVIDER_ERROR') {
        return 'Dịch vụ mô hình AI gặp sự cố trong quá trình tạo câu trả lời. Vui lòng thử lại sau.';
      }
      if (reasonCode === 'TIMEOUT') {
        return 'Yêu cầu tra cứu quá thời gian chờ. Vui lòng thử lại sau.';
      }
      if (reasonCode === 'RULE_BLOCKED') {
        return 'Câu hỏi bị từ chối do chính sách an toàn của hệ thống.';
      }

      if (rawErrorMsg && !rawErrorMsg.startsWith('Request failed with status')) {
        return 'Không thể hoàn tất tra cứu. Vui lòng thử lại.';
      }
    }

    // 2. HTTP Status Code Mappings
    const errorCode = error.errorResponse?.errorCode;
    if (error.status === 400) {
      if (errorCode === 'VALIDATION_FAILED' || (error.message && error.message.includes('studentId'))) {
        return 'Yêu cầu không hợp lệ. Vui lòng kiểm tra lại câu hỏi hoặc thông tin học viên.';
      }
      return error.message || 'Yêu cầu không hợp lệ. Vui lòng kiểm tra lại câu hỏi.';
    }
    if (error.status === 401) {
      return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
    }
    if (errorCode === 'AI_FORBIDDEN' || (error.message && error.message.includes('học viên'))) {
      if (error.errorResponse?.message) {
        return error.errorResponse.message;
      }
      if (error.message && !error.message.startsWith('Request failed with status') && error.message !== 'Forbidden') {
        return error.message;
      }
      return 'Bạn không có quyền truy cập dữ liệu cá nhân của học viên này.';
    }
    if (error.status === 403) {
      return 'Bạn không có quyền truy cập tính năng tra cứu kiến thức AI.';
    }
    if (error.status === 404) {
      return 'Bài tập hoặc tài liệu tham chiếu không tồn tại trong hệ thống.';
    }
    if (error.status === 503) {
      return 'Tính năng Trợ lý Kiến thức Thể hình tạm thời gián đoạn theo lịch vận hành hệ thống.';
    }
    if (error.status === 504) {
      return 'Yêu cầu tra cứu quá thời gian chờ. Vui lòng thử lại sau.';
    }
    if (error.status === 502) {
      return 'Hệ thống AI đang bảo trì hoặc gặp sự cố phản hồi. Vui lòng thử lại sau ít phút.';
    }
    if (error.status === 500) {
      return 'Lỗi hệ thống trong quá trình xử lý câu hỏi. Vui lòng thử lại sau.';
    }
    if (error.status === 0) {
      return 'Mất kết nối mạng. Vui lòng kiểm tra đường truyền và thử lại.';
    }
    if (error.message && !error.message.startsWith('Request failed with status')) {
      return error.message;
    }
    return `Lỗi hệ thống (${error.status}). Vui lòng thử lại sau.`;
  }

  if (error instanceof Error && error.message) {
    return error.message;
  }

  return 'Không thể kết nối với hệ thống. Vui lòng thử lại.';
}

export interface QueryKnowledgeOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
}

export const aiKnowledgeApi = {
  /**
   * Submits a fitness knowledge inquiry to Spring Boot backend.
   * Path: POST /api/v1/ai/knowledge/query
   *
   * @param params Query parameters conforming to F01KnowledgeQueryRequest
   * @param options Optional client settings (e.g. timeoutMs, signal)
   * @returns F01KnowledgeQueryResponse
   */
  async queryKnowledge(
    params: F01KnowledgeQueryRequest,
    options: QueryKnowledgeOptions = {}
  ): Promise<F01KnowledgeQueryResponse> {
    const trimmedQuestion = params.question?.trim() ?? '';
    const codePointLen = countCodePoints(trimmedQuestion);

    if (codePointLen < F01_QUESTION_MIN_LENGTH) {
      throw new AiKnowledgeValidationError(
        `Câu hỏi phải có ít nhất ${F01_QUESTION_MIN_LENGTH} ký tự.`
      );
    }

    if (codePointLen > F01_QUESTION_MAX_LENGTH) {
      throw new AiKnowledgeValidationError(
        `Câu hỏi không được vượt quá ${F01_QUESTION_MAX_LENGTH} ký tự.`
      );
    }

    const payload: F01KnowledgeQueryRequest = {
      question: trimmedQuestion,
      language: params.language ?? 'vi',
      ...(params.exerciseId ? { exerciseId: params.exerciseId } : {}),
      ...(params.correlationId ? { correlationId: params.correlationId } : {}),
      ...(params.studentId ? { studentId: params.studentId } : {}),
      ...(typeof params.includePersonalContext === 'boolean'
        ? { includePersonalContext: params.includePersonalContext }
        : {}),
    };

    const externalSignal = options.signal;
    if (externalSignal?.aborted) {
      throw new Error('Yêu cầu đã bị hủy.');
    }

    // Client-side timeout using AbortController
    const timeoutMs = options.timeoutMs ?? F01_DEFAULT_TIMEOUT_MS;
    const controller = new AbortController();
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    const abortHandler = () => {
      controller.abort();
    };
    if (externalSignal) {
      externalSignal.addEventListener('abort', abortHandler, { once: true });
    }

    try {
      timeoutId = setTimeout(() => {
        controller.abort();
      }, timeoutMs);

      return await request<F01KnowledgeQueryResponse>('/ai/knowledge/query', {
        method: 'POST',
        body: payload,
        signal: controller.signal,
      });
    } catch (err: unknown) {
      if (externalSignal?.aborted) {
        throw new Error('Yêu cầu đã bị hủy.');
      }
      if (controller.signal.aborted) {
        throw new Error('Yêu cầu phản hồi quá thời gian chờ. Vui lòng thử lại câu hỏi ngắn gọn hơn.');
      }
      throw err;
    } finally {
      clearTimeout(timeoutId);
      if (externalSignal) {
        externalSignal.removeEventListener('abort', abortHandler);
      }
    }
  },

  /**
   * Retrieves an AI run by runId for readback / audit validation.
   * Path: GET /api/v1/ai/runs/{runId}
   *
   * @param runId UUID of the AI Run
   * @param signal Optional AbortSignal
   * @returns AiRunResponse
   */
  async getRun(runId: string, signal?: AbortSignal): Promise<AiRunResponse> {
    if (!runId || !runId.trim()) {
      throw new AiKnowledgeValidationError('Mã lần xử lý không được để trống.');
    }
    return await request<AiRunResponse>(`/ai/runs/${encodeURIComponent(runId.trim())}`, {
      method: 'GET',
      signal,
    });
  },
};
