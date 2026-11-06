import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import {
  F04ProgressSummaryApiRequest,
  F04ProgressSummaryResponse,
} from '@/types/aiProgressSummary';

export const F04_DEFAULT_TIMEZONE = 'Asia/Ho_Chi_Minh';

export const METRIC_OPTIONS: Array<{ code: string; label: string }> = [
  { code: 'F_WEIGHT', label: 'Cân nặng (kg)' },
  { code: 'F_BODY_FAT', label: 'Tỷ lệ mỡ (%)' },
  { code: 'F_WAIST', label: 'Vòng eo (cm)' },
  { code: 'F_CHEST', label: 'Vòng ngực (cm)' },
];

export const CONTINUITY_STATE_LABELS: Record<string, string> = {
  CONFIRMED_NEW: 'Học viên mới bắt đầu',
  PROLONGED_INACTIVITY: 'Gián đoạn tập luyện dài hạn',
  RECENT_ACTIVITY_OBSERVED: 'Có hoạt động tập luyện gần đây',
  UNKNOWN_HISTORY: 'Lịch sử chưa xác định đầy đủ',
  RESTRICTED_HISTORY: 'Lịch sử bị giới hạn quyền truy cập',
};

export const TREND_DIRECTION_LABELS: Record<string, { label: string; symbol: string }> = {
  INCREASED: { label: 'Tăng', symbol: '↑' },
  DECREASED: { label: 'Giảm', symbol: '↓' },
  STABLE: { label: 'Ổn định', symbol: '→' },
  INSUFFICIENT_DATA: { label: 'Chưa đủ dữ liệu xu hướng', symbol: '—' },
};

export function getAiProgressSummaryErrorMessage(error: unknown): string {
  if (
    error instanceof Error &&
    (error.name === 'AbortError' || error.message.includes('aborted') || error.message.includes('timeout'))
  ) {
    return 'Yêu cầu tóm tắt quá thời gian chờ (timeout). Vui lòng thử lại sau.';
  }

  if (error instanceof ApiError) {
    const code = error.errorResponse?.errorCode;

    if (code === 'AI_RUN_ACCESS_DENIED' || error.status === 403) {
      return 'Bạn không có quyền truy cập dữ liệu tiến độ của học viên này hoặc tài khoản/quan hệ huấn luyện đã kết thúc.';
    }

    if (code === 'STUDENT_NOT_FOUND' || error.status === 404) {
      return 'Không tìm thấy hồ sơ học viên hoặc bản tóm tắt tiến độ yêu cầu.';
    }

    if (code === 'VALIDATION_FAILED' || code === 'BAD_REQUEST' || error.status === 400) {
      const fieldMsg = error.errorResponse?.fieldErrors?.map((f) => f.message).join(', ');
      if (fieldMsg) {
        return `Thông tin yêu cầu không hợp lệ: ${fieldMsg}`;
      }
      return 'Khoảng ngày không hợp lệ. Vui lòng đảm bảo ngày bắt đầu không sau ngày kết thúc.';
    }

    if (code === 'AI_OUTPUT_VALIDATION_FAILED' || error.status === 422) {
      return 'Kết quả từ AI không vượt qua kiểm định an toàn và tính toàn vẹn dữ liệu.';
    }

    if (code === 'AI_FEATURE_DISABLED') {
      return 'Tính năng tóm tắt tiến độ AI hiện đang tạm tắt theo cấu hình quản trị.';
    }

    if (error.status === 502 || error.status === 503 || error.status === 504) {
      return 'Dịch vụ phân tích AI tạm thời không khả dụng hoặc quá thời gian chờ. Vui lòng thử lại.';
    }

    if (error.status === 0) {
      return 'Mất kết nối mạng. Vui lòng kiểm tra kết nối internet và thử lại.';
    }

    if (error.message && !error.message.startsWith('Request failed with status')) {
      return error.message;
    }
  }

  if (error instanceof Error && error.message) {
    return error.message;
  }

  return 'Đã xảy ra lỗi không xác định khi tạo bản tóm tắt tiến độ.';
}

export const aiProgressSummaryApi = {
  generate(requestPayload: F04ProgressSummaryApiRequest, signal?: AbortSignal) {
    return request<F04ProgressSummaryResponse>('/ai/progress/summary', {
      method: 'POST',
      body: {
        ...requestPayload,
        timezone: requestPayload.timezone || F04_DEFAULT_TIMEZONE,
      },
      signal,
    });
  },

  getSummary(runId: string, signal?: AbortSignal) {
    return request<F04ProgressSummaryResponse>(`/ai/progress/summary/${runId}`, {
      method: 'GET',
      signal,
    });
  },
};
