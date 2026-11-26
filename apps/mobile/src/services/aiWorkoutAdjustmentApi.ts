import { request } from '@/services/apiClient';
import { localizeFieldErrorsByCode } from '@/localization/fieldErrorMessages';
import { createCommandKey } from '@/services/coachingApi';
import {
  WorkoutAdjustmentApiRequest,
  WorkoutAdjustmentApiResponse,
  AcceptAdjustmentApiRequest,
  AcceptAdjustmentApiResponse,
  RejectAdjustmentApiRequest,
  RejectAdjustmentApiResponse,
  AiWorkoutAdjustmentDetail,
  UpdateAdjustmentDraftApiRequest,
  UpdateAdjustmentDraftApiResponse,
  ShareAdjustmentDraftApiRequest,
  ShareAdjustmentDraftApiResponse,
} from '@/types/aiWorkoutAdjustment';
import { ApiError } from '@/types/auth';

export function getWorkoutAdjustmentFieldErrors(error: unknown): Record<string, string> {
  if (error instanceof ApiError && error.errorResponse?.fieldErrors) {
    return localizeFieldErrorsByCode(error.errorResponse.fieldErrors);
  }
  return {};
}

/**
 * Backend diagnostic text from ErrorResponse.message. apiClient replaces ApiError.message with a
 * generic Vietnamese sentence, so the diagnostic only lives here. Used for classification only and
 * never displayed; a non-string value (object/null/number) is ignored, not coerced to text.
 */
function backendDiagnostic(error: ApiError): string {
  const value: unknown = error.errorResponse?.message;
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

/**
 * Snapshot diagnostics verified in AiWorkoutAdjustmentService (409 AI_RUN_LIFECYCLE_CONFLICT):
 * - SHARED: "Shared snapshot data is missing or corrupted." / "Shared snapshot payload is corrupted."
 *   (data saved when the trainer shared the draft)
 * - SOURCE: "Missing <field> in source snapshot." / "Source snapshot is malformed…: <detail>"
 *   (source data recorded when the proposal was created)
 * Anything else, including other text containing "snapshot", is not classified.
 */
function classifySnapshotDiagnostic(diagnostic: string): 'SHARED' | 'SOURCE' | null {
  if (/^shared snapshot (data is missing or corrupted|payload is corrupted)\b/.test(diagnostic)) return 'SHARED';
  if (/^missing [a-z_]+ in source snapshot\b/.test(diagnostic) || /^source snapshot is malformed\b/.test(diagnostic)) {
    return 'SOURCE';
  }
  return null;
}

/**
 * Fixed copy for 409 AI_RUN_LIFECYCLE_CONFLICT diagnostics verified in AiWorkoutAdjustmentService.
 * Patterns are anchored to the exact Backend sentences (lower-cased); anything else stays generic.
 */
const LIFECYCLE_DIAGNOSTIC_COPY: { pattern: RegExp; copy: string }[] = [
  {
    // "Recommendation has expired and cannot be accepted|rejected|edited|shared."
    pattern: /^recommendation has expired and cannot be (accepted|rejected|edited|shared)\.?$/,
    copy: 'Đề xuất điều chỉnh đã hết hạn nên không thể tiếp tục thao tác.',
  },
  {
    // "Cannot adjust occurrence with status X (only SCHEDULED occurrences can be adjusted)."
    pattern: /^cannot adjust occurrence with status [a-z_]+ \(only scheduled occurrences can be adjusted\)\.?$/,
    copy: 'Chỉ có thể điều chỉnh buổi tập đang ở trạng thái đã lên lịch.',
  },
  {
    // "Cannot share adjustment for occurrence with status X (only SCHEDULED occurrences can be shared)."
    pattern: /^cannot share adjustment for occurrence with status [a-z_]+ \(only scheduled occurrences can be shared\)\.?$/,
    copy: 'Chỉ có thể chia sẻ đề xuất cho buổi tập đang ở trạng thái đã lên lịch.',
  },
  {
    // "Idempotency conflict: commandKey matches a previous command with differing parameters."
    pattern: /^idempotency conflict: commandkey matches a previous command with differing parameters\.?$/,
    copy: 'Xung đột thao tác (trùng khóa lệnh nhưng nội dung hoặc phiên bản kỳ vọng khác nhau).',
  },
  {
    // "Revision conflict: expected revision N but current is M" /
    // "Revision conflict: draft has been modified to revision M; expectedRevision is required when accepting."
    pattern:
      /^revision conflict: (expected revision (\d+|null) but current is \d+|draft has been modified to revision \d+; expectedrevision is required when accepting)\b/,
    copy: 'Bản nháp đã được cập nhật sang phiên bản khác (xung đột phiên bản). Vui lòng tải lại đề xuất mới nhất.',
  },
  {
    // "Recommendation has already been applied."
    pattern: /^recommendation has already been applied\.?$/,
    copy: 'Đề xuất điều chỉnh này đã được áp dụng trước đó.',
  },
];

/** Fixed copy for 403 AI_FORBIDDEN diagnostics verified in AiWorkoutAdjustmentService. */
const FORBIDDEN_DIAGNOSTIC_COPY: { pattern: RegExp; copy: string }[] = [
  {
    // "Access denied: Private trainer draft has not been shared with the student."
    pattern: /^access denied: private trainer draft has not been shared with the student\.?$/,
    copy: 'Bản nháp của Huấn luyện viên chưa được chia sẻ cho học viên.',
  },
  {
    // "Draft sharing is only supported in HUMAN_COACH mode."
    pattern: /^draft sharing is only supported in human_coach mode\.?$/,
    copy: 'Chỉ có thể chia sẻ bản nháp ở chế độ Có huấn luyện viên.',
  },
  {
    // "Only the currently assigned coach has authority to edit this recommendation." /
    // "Only the student has authority to edit this recommendation."
    pattern: /^only the (currently assigned coach|student) has authority to edit this recommendation\.?$/,
    copy: 'Bạn không có thẩm quyền chỉnh sửa đề xuất điều chỉnh này trong chế độ hiện tại.',
  },
  {
    // "Only the currently assigned coach has authority to share drafts with the student."
    pattern: /^only the currently assigned coach has authority to share drafts with the student\.?$/,
    copy: 'Chỉ huấn luyện viên đang phụ trách mới có thể chia sẻ bản nháp cho học viên.',
  },
];

function copyForDiagnostic(diagnostic: string, table: { pattern: RegExp; copy: string }[]): string | null {
  if (!diagnostic) return null;
  return table.find((entry) => entry.pattern.test(diagnostic))?.copy ?? null;
}

export function getWorkoutAdjustmentErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const code = error.errorResponse?.errorCode;
    if (code === 'PLANNED_WORKOUT_ADJUSTMENTS_SEALED') {
      return 'Buổi tập đã bắt đầu hoặc hoàn thành, không thể điều chỉnh.';
    }
    if (code === 'PLANNED_WORKOUT_VERSION_CONFLICT') {
      return 'Phiên bản buổi tập đã bị thay đổi trên hệ thống (xung đột phiên bản). Vui lòng tải lại và xem xét lại đề xuất.';
    }
    if (code === 'WORKOUT_PLAN_VERSION_CONFLICT') {
      return 'Phiên bản kế hoạch đã bị thay đổi trên hệ thống. Vui lòng tải lại và xem xét lại đề xuất.';
    }
    if (code === 'WORKOUT_PLAN_EXERCISE_UNAVAILABLE') {
      return 'Biến thể bài tập thay thế hiện không khả dụng trong danh mục bài tập.';
    }
    if (code === 'AI_RUN_LIFECYCLE_CONFLICT') {
      const snapshotIssue = error.status === 409 ? classifySnapshotDiagnostic(backendDiagnostic(error)) : null;
      if (snapshotIssue === 'SHARED') {
        return 'Dữ liệu của bản nháp đã chia sẻ (được lưu tại thời điểm chia sẻ) bị thiếu hoặc hỏng. Vui lòng liên hệ Huấn luyện viên để chia sẻ lại.';
      }
      if (snapshotIssue === 'SOURCE') {
        return 'Dữ liệu nguồn được lưu khi tạo đề xuất bị thiếu hoặc không hợp lệ. Vui lòng tạo đề xuất mới.';
      }
      const lifecycleCopy =
        error.status === 409 ? copyForDiagnostic(backendDiagnostic(error), LIFECYCLE_DIAGNOSTIC_COPY) : null;
      if (lifecycleCopy) {
        return lifecycleCopy;
      }
      return 'Xung đột trạng thái đề xuất điều chỉnh. Vui lòng tải lại trang và xem xét lại.';
    }
    if (code === 'AI_OUTPUT_VALIDATION_FAILED' || error.status === 422) {
      return 'Đề xuất từ AI không vượt qua kiểm định an toàn bài tập hoặc danh mục bài tập.';
    }
    if (code === 'VALIDATION_FAILED' || code === 'BAD_REQUEST') {
      if (error.errorResponse?.fieldErrors && error.errorResponse.fieldErrors.length > 0) {
        return 'Vui lòng kiểm tra và hoàn thiện các trường thông tin bắt buộc còn thiếu bên dưới.';
      }
    }
    if (error.status === 403 || code === 'AI_RUN_ACCESS_DENIED' || code === 'AI_FORBIDDEN') {
      const forbiddenCopy =
        error.status === 403 && code === 'AI_FORBIDDEN'
          ? copyForDiagnostic(backendDiagnostic(error), FORBIDDEN_DIAGNOSTIC_COPY)
          : null;
      if (forbiddenCopy) {
        return forbiddenCopy;
      }
      return 'Bạn không có thẩm quyền quyết định đối với đề xuất điều chỉnh này trong chế độ huấn luyện hiện tại.';
    }
    if (error.status === 404) {
      return 'Không tìm thấy đề xuất điều chỉnh yêu cầu.';
    }
    if (error.status === 409) {
      return 'Xung đột dữ liệu với hệ thống. Vui lòng tải lại dữ liệu mới nhất và xem xét lại đề xuất.';
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
  return 'Lỗi không xác định khi xử lý đề xuất điều chỉnh giáo án.';
}

export const aiWorkoutAdjustmentApi = {
  generate(input: WorkoutAdjustmentApiRequest) {
    const payload = {
      ...input,
      commandKey: input.commandKey || createCommandKey(),
    };
    return request<WorkoutAdjustmentApiResponse>('/ai/workout-adjustments', {
      method: 'POST',
      body: payload,
    });
  },

  getRecommendation(recommendationId: string) {
    return request<AiWorkoutAdjustmentDetail>(`/ai/workout-adjustments/${encodeURIComponent(recommendationId)}`, {
      method: 'GET',
    });
  },

  updateDraft(recommendationId: string, body: UpdateAdjustmentDraftApiRequest) {
    return request<UpdateAdjustmentDraftApiResponse>(
      `/ai/workout-adjustments/${encodeURIComponent(recommendationId)}/draft`,
      {
        method: 'PUT',
        body,
      }
    );
  },

  shareDraft(recommendationId: string, body: ShareAdjustmentDraftApiRequest) {
    return request<ShareAdjustmentDraftApiResponse>(
      `/ai/workout-adjustments/${encodeURIComponent(recommendationId)}/share`,
      {
        method: 'POST',
        body,
      }
    );
  },

  accept(recommendationId: string, body?: AcceptAdjustmentApiRequest) {
    return request<AcceptAdjustmentApiResponse>(`/ai/workout-adjustments/${encodeURIComponent(recommendationId)}/accept`, {
      method: 'POST',
      body: body || {},
    });
  },

  reject(recommendationId: string, body?: RejectAdjustmentApiRequest) {
    return request<RejectAdjustmentApiResponse>(`/ai/workout-adjustments/${encodeURIComponent(recommendationId)}/reject`, {
      method: 'POST',
      body: body || {},
    });
  },
};
