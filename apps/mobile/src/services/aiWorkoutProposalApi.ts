import { request } from '@/services/apiClient';
import { createCommandKey } from '@/services/coachingApi';
import {
  WorkoutProposalResponse,
  AiRecommendationDetail,
  AcceptRecommendationResponse,
  RejectRecommendationResponse,
  UpdateRecommendationDraftRequest,
  UpdateRecommendationDraftResponse,
} from '@/types/aiWorkoutProposal';
import { ApiError } from '@/types/auth';

export function getWorkoutProposalErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = error.errorResponse?.errorCode;
    if (code === 'ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS') {
      return 'Học viên đã có một kế hoạch tập luyện đang áp dụng. Hệ thống không cho phép áp dụng đồng thời nhiều kế hoạch tập luyện.';
    }
    if (code === 'WORKOUT_PLAN_SUCCESSOR_REQUIRED') {
      return 'Kế hoạch hiện tại do huấn luyện viên quản lý. Bạn cần tạo bản kế thừa của riêng mình trước khi cập nhật.';
    }
    if (code === 'WORKOUT_PLAN_VERSION_CONFLICT') {
      return 'Phiên bản kế hoạch đã bị thay đổi trên hệ thống. Vui lòng tải lại và thử lại.';
    }
    if (code === 'WORKOUT_PLAN_ACCESS_DENIED') {
      return 'Bạn không có quyền chỉnh sửa kế hoạch tập luyện này trong chế độ hiện tại.';
    }
    if (code === 'AI_RUN_LIFECYCLE_CONFLICT') {
      if (error.message.includes('expired')) {
        return 'Đề xuất giáo án đã hết hạn (quá 7 ngày). Vui lòng tạo đề xuất mới.';
      }
      if (error.message.includes('already been applied')) {
        return 'Đề xuất giáo án này đã được áp dụng trước đó.';
      }
      if (error.message.includes('Idempotency conflict')) {
        return 'Xung đột thao tác (trùng khóa lệnh nhưng nội dung khác nhau).';
      }
      if (error.message.includes('Revision conflict')) {
        return 'Bản thảo đã bị chỉnh sửa ở một phiên khác (xung đột phiên bản). Vui lòng tải lại đề xuất mới nhất.';
      }
      return 'Xung đột trạng thái đề xuất giáo án. Vui lòng tải lại trang và thử lại.';
    }
    if (code === 'AI_OUTPUT_VALIDATION_FAILED' || error.status === 422) {
      return 'Đề xuất từ AI không vượt qua kiểm định an toàn bài tập hoặc cấu trúc 1 tuần.';
    }
    if (error.status === 403) {
      return 'Bạn không có thẩm quyền quyết định đối với đề xuất giáo án này trong chế độ huấn luyện hiện tại.';
    }
    if (error.status === 404) {
      return 'Không tìm thấy đề xuất giáo án yêu cầu.';
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
  return 'Lỗi không xác định khi xử lý đề xuất giáo án.';
}

export const aiWorkoutProposalApi = {
  generate(input: { studentId?: string; preferences?: Record<string, unknown> }, commandKey = createCommandKey()) {
    return request<WorkoutProposalResponse>('/ai/workout-proposals', {
      method: 'POST',
      body: { ...input, commandKey },
    });
  },

  getRecommendation(recommendationId: string) {
    return request<AiRecommendationDetail>(`/ai/recommendations/${recommendationId}`, {
      method: 'GET',
    });
  },

  updateDraft(recommendationId: string, input: UpdateRecommendationDraftRequest) {
    return request<UpdateRecommendationDraftResponse>(`/ai/recommendations/${recommendationId}/draft`, {
      method: 'PUT',
      body: input,
    });
  },

  accept(
    recommendationId: string,
    decisionNote?: string,
    commandKey = createCommandKey(),
    expectedRevision?: number,
    expectedPlanId?: string,
    expectedPlanAggregateVersion?: number,
    expectedNoActivePlan?: boolean
  ) {
    const body: Record<string, unknown> = { commandKey };
    if (decisionNote !== undefined) body.decisionNote = decisionNote;
    if (expectedRevision !== undefined) body.expectedRevision = expectedRevision;
    if (expectedPlanId !== undefined) body.expectedPlanId = expectedPlanId;
    if (expectedPlanAggregateVersion !== undefined) body.expectedPlanAggregateVersion = expectedPlanAggregateVersion;
    if (expectedNoActivePlan !== undefined) body.expectedNoActivePlan = expectedNoActivePlan;

    return request<AcceptRecommendationResponse>(`/ai/recommendations/${recommendationId}/accept`, {
      method: 'POST',
      body,
    });
  },

  reject(recommendationId: string, reason?: string) {
    return request<RejectRecommendationResponse>(`/ai/recommendations/${recommendationId}/reject`, {
      method: 'POST',
      body: { reason },
    });
  },
};
