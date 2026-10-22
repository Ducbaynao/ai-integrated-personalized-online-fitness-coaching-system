import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import {
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
 * Avoids collapsing all 502s to model errors when the failure may be output validation.
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
    return 'Yêu cầu tra cứu quá thời gian chờ (timeout). Vui lòng thử lại sau.';
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
        return 'Yêu cầu tra cứu quá thời gian chờ (timeout). Vui lòng thử lại sau.';
      }
      if (reasonCode === 'RULE_BLOCKED') {
        return 'Câu hỏi bị từ chối do chính sách an toàn của hệ thống.';
      }

      if (rawErrorMsg && !rawErrorMsg.startsWith('Request failed with status')) {
        return rawErrorMsg;
      }
    }

    // 2. HTTP Status Code Mappings
    if (error.status === 401) {
      return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
    }
    if (error.status === 403) {
      return 'Bạn không có quyền truy cập tính năng tra cứu kiến thức AI.';
    }
    if (error.status === 404) {
      return 'Bài tập hoặc tài liệu tham chiếu không tồn tại trong hệ thống.';
    }
    if (error.status === 504) {
      return 'Yêu cầu tra cứu quá thời gian chờ (timeout). Vui lòng thử lại sau.';
    }
    if (error.status === 502) {
      return 'Dịch vụ AI phản hồi không hợp lệ hoặc không vượt qua kiểm định an toàn.';
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
}

export const aiKnowledgeApi = {
  /**
   * Submits a general fitness knowledge inquiry to Spring Boot backend.
   * Path: POST /api/v1/ai/knowledge/query
   *
   * @param params Query parameters conforming to F01KnowledgeQueryRequest
   * @param options Optional client settings (e.g. timeoutMs)
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
    };

    // Client-side timeout using AbortController
    const timeoutMs = options.timeoutMs ?? F01_DEFAULT_TIMEOUT_MS;
    const controller = new AbortController();
    const timeoutId = setTimeout(() => {
      controller.abort();
    }, timeoutMs);

    try {
      return await request<F01KnowledgeQueryResponse>('/ai/knowledge/query', {
        method: 'POST',
        body: payload,
        signal: controller.signal,
      });
    } catch (err: unknown) {
      if (controller.signal.aborted) {
        throw new Error('Yêu cầu tra cứu quá thời gian chờ (timeout). Vui lòng thử lại sau.');
      }
      throw err;
    } finally {
      clearTimeout(timeoutId);
    }
  },
};
