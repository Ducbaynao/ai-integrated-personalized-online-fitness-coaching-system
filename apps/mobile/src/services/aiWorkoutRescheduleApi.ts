import { request } from '@/services/apiClient';
import { localizedServerMessage } from '@/localization/displayLabels';
import { createCommandKey } from '@/services/coachingApi';
import type { AiWorkoutRescheduleDetail } from '@/types/aiWorkoutReschedule';
import { ApiError } from '@/types/auth';
import {
  AcceptRescheduleApiRequest,
  AcceptRescheduleApiResponse,
  RejectRescheduleApiRequest,
  RejectRescheduleApiResponse,
  WorkoutRescheduleApiRequest,
  WorkoutRescheduleApiResponse,
  WorkoutRescheduleDetailApiResponse,
  WorkoutRescheduleFailureKind,
} from '@/types/aiWorkoutReschedule';

/** Preserve the underlying ApiError (including errorCode/fieldErrors) for consumers. */
export function getWorkoutRescheduleFailureKind(error: unknown): WorkoutRescheduleFailureKind {
  if (error instanceof ApiError) {
    if (error.status === 400) return 'VALIDATION';
    if (error.status === 401) return 'AUTHENTICATION';
    if (error.status === 403) return 'AUTHORITY';
    if (error.status === 404) return 'NOT_FOUND';
    if (error.status === 409) return 'CONFLICT';
    if (error.status === 408 || error.status === 504 || error.errorResponse?.errorCode === 'TIMEOUT') return 'TIMEOUT';
    if (error.status === 0) return 'NETWORK';
  }
  if (error instanceof Error) {
    if (error.name === 'AbortError') return 'CANCELLED';
    if (error.name === 'TimeoutError' || /timeout|timed out/i.test(error.message)) return 'TIMEOUT';
  }
  return 'OTHER';
}

function requireCommandKey(commandKey: string): void {
  if (!commandKey?.trim()) {
    throw new Error('commandKey is required for this workout reschedule intent.');
  }
}

export function getWorkoutRescheduleFieldErrors(error: unknown): Record<string, string> {
  if (error instanceof ApiError && error.errorResponse?.fieldErrors) {
    const map: Record<string, string> = {};
    for (const fe of error.errorResponse.fieldErrors) {
      if (fe.field && fe.message) {
        map[fe.field] = localizedServerMessage(fe.message, 'Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.');
      }
    }
    return map;
  }
  return {};
}

export function getWorkoutRescheduleErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = error.errorResponse?.errorCode;
    const msg = error.message || '';

    if (code === 'WORKOUT_SCHEDULE_OVERLAP') {
      return 'Khung giờ đề xuất bị trùng lặp với một buổi tập khác trong lịch. Vui lòng tạo đề xuất dời lịch mới.';
    }
    if (code === 'PLANNED_WORKOUT_VERSION_CONFLICT') {
      return 'Phiên bản buổi tập đã thay đổi trên máy chủ. Vui lòng làm mới lịch và thử lại.';
    }
    if (code === 'APPOINTMENT_VERSION_CONFLICT') {
      return 'Phiên bản lịch hẹn liên kết đã thay đổi. Vui lòng tạo đề xuất mới để phù hợp với lịch hẹn mới nhất.';
    }
    if (code === 'WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED') {
      return 'Buổi tập bắt buộc có huấn luyện viên (COACH_REQUIRED) nhưng không có lịch hẹn hợp lệ bao trọn. Vui lòng đổi lịch hẹn trước rồi tạo đề xuất mới.';
    }
    if (code === 'APPOINTMENT_COACH_REQUIRED_DEPENDENCY') {
      return 'Lịch hẹn không thể đổi sang khoảng giờ rời khỏi buổi tập bắt buộc có huấn luyện viên hiện tại. Cần phối hợp với huấn luyện viên trước.';
    }
    if (code === 'AI_RUN_LIFECYCLE_CONFLICT') {
      if (msg.includes('expired')) {
        return 'Đề xuất dời lịch đã hết hạn (quá 30 phút). Vui lòng tạo đề xuất mới.';
      }
      if (msg.includes('already been applied')) {
        return 'Đề xuất dời lịch này đã được áp dụng trước đó.';
      }
      if (msg.includes('already been decided') || msg.includes('already decided')) {
        return 'Đề xuất dời lịch này đã được quyết định trước đó.';
      }
      if (msg.includes('Idempotency conflict') || msg.includes('differing')) {
        return 'Xung đột thao tác (trùng khóa lệnh nhưng thông số hoặc phiên bản kỳ vọng khác nhau).';
      }
      if (msg.includes('occurrence version') || msg.includes('version conflict')) {
        return 'Phiên bản buổi tập không khớp với đề xuất. Vui lòng tạo đề xuất mới.';
      }
      if (msg.includes('appointment') || msg.includes('Appointment')) {
        return 'Lịch hẹn liên kết đã thay đổi hoặc không ở trạng thái CONFIRMED. Vui lòng tạo đề xuất mới.';
      }
      return 'Xung đột trạng thái đề xuất dời lịch. Vui lòng tải lại và thử lại.';
    }
    if (code === 'AI_OUTPUT_VALIDATION_FAILED' || error.status === 422) {
      return 'Đề xuất từ AI không vượt qua kiểm định an toàn hoặc quy tắc thời gian.';
    }
    if (error.status === 403 || code === 'AI_RUN_ACCESS_DENIED' || code === 'AI_FORBIDDEN') {
      if (msg.includes('HUMAN_COACH') || msg.includes('coach')) {
        return 'Trong chế độ có huấn luyện viên (HUMAN_COACH), chỉ huấn luyện viên mới có quyền quyết định áp dụng đổi lịch.';
      }
      return 'Bạn không có thẩm quyền đối với đề xuất dời lịch này trong chế độ hiện tại.';
    }
    if (error.status === 404) {
      return 'Không tìm thấy đề xuất dời lịch hoặc buổi tập yêu cầu.';
    }
    if (error.status === 409) {
      return 'Xung đột trạng thái với máy chủ. Vui lòng tải lại dữ liệu mới nhất.';
    }
    if (error.status === 0) {
      return 'Mất kết nối mạng. Vui lòng kiểm tra kết nối và thử lại.';
    }
    if (error.message) {
      return error.message;
    }
  }
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return 'Lỗi không xác định khi xử lý đề xuất dời lịch.';
}

export const aiWorkoutRescheduleApi = {
  generate(input: WorkoutRescheduleApiRequest | Omit<WorkoutRescheduleApiRequest, 'commandKey'>, commandKey?: string) {
    const body = 'commandKey' in input ? input : { ...input, commandKey: commandKey ?? createCommandKey() };
    requireCommandKey(body.commandKey);
    return request<WorkoutRescheduleApiResponse>('/ai/workout-reschedules', {
      method: 'POST',
      body,
    });
  },

  getDetail(recommendationId: string) {
    return request<AiWorkoutRescheduleDetail>(`/ai/workout-reschedules/${encodeURIComponent(recommendationId)}`, { method: 'GET' });
  },

  getRecommendation(recommendationId: string) {
    return request<WorkoutRescheduleDetailApiResponse>(
      `/ai/workout-reschedules/${encodeURIComponent(recommendationId)}`,
      { method: 'GET' }
    );
  },

  accept(recommendationId: string, input: AcceptRescheduleApiRequest | Omit<AcceptRescheduleApiRequest, 'commandKey'>, commandKey?: string) {
    const body = 'commandKey' in input ? input : { ...input, commandKey: commandKey ?? createCommandKey() };
    requireCommandKey(body.commandKey);
    return request<AcceptRescheduleApiResponse>(
      `/ai/workout-reschedules/${encodeURIComponent(recommendationId)}/accept`,
      { method: 'POST', body }
    );
  },

  reject(recommendationId: string, body?: RejectRescheduleApiRequest) {
    return request<RejectRescheduleApiResponse>(
      `/ai/workout-reschedules/${encodeURIComponent(recommendationId)}/reject`,
      { method: 'POST', ...(body === undefined ? {} : { body }) }
    );
  },
};
