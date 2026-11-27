import type { Gender, TrainingExperienceLevel } from '@/types/student';
import type { CoachingBlockingReason, TrainerActivityStatus, TrainerVerificationStatus } from '@/types/trainer';
import type { GoalLifecycleStatus, ProposalStatus } from '@/types/goal';
import type { AdjustmentRecommendationStatus } from '@/types/aiWorkoutAdjustment';

export const genderLabels: Record<Gender, string> = {
  MALE: 'Nam',
  FEMALE: 'Nữ',
  NON_BINARY: 'Phi nhị nguyên giới',
  OTHER: 'Khác',
  PREFER_NOT_TO_SAY: 'Không muốn trả lời',
};

export const experienceLabels: Record<TrainingExperienceLevel, string> = {
  BEGINNER: 'Mới bắt đầu',
  INTERMEDIATE: 'Trung cấp',
  ADVANCED: 'Nâng cao',
};

export const verificationLabels: Record<TrainerVerificationStatus, string> = {
  NOT_SUBMITTED: 'Chưa nộp hồ sơ',
  PENDING: 'Đang chờ xác minh',
  VERIFIED: 'Đã xác minh',
  REJECTED: 'Bị từ chối',
  SUSPENDED: 'Bị tạm ngưng',
};

export const trainerActivityLabels: Record<TrainerActivityStatus, string> = {
  ACTIVE: 'Đang hoạt động',
  INACTIVE: 'Chưa hoạt động',
  SUSPENDED: 'Bị tạm ngưng',
};

export const coachingBlockingLabels: Record<CoachingBlockingReason, string> = {
  PROFILE_NOT_FOUND: 'Chưa có hồ sơ huấn luyện viên',
  APPLICATION_NOT_SUBMITTED: 'Chưa nộp đơn xác minh',
  VERIFICATION_PENDING: 'Đang chờ xác minh',
  VERIFICATION_REJECTED: 'Hồ sơ xác minh bị từ chối',
  VERIFICATION_EXPIRED: 'Xác minh đã hết hạn',
  VERIFICATION_REVOKED: 'Xác minh đã bị thu hồi',
  ACTIVITY_SUSPENDED: 'Hoạt động bị tạm ngưng',
  ACTIVITY_INACTIVE: 'Tài khoản chưa hoạt động',
  POLICY_RESTRICTION: 'Bị hạn chế theo chính sách',
};

export const goalStatusLabels: Record<GoalLifecycleStatus, string> = {
  DRAFT: 'Bản nháp',
  ACTIVE: 'Đang hoạt động',
  PAUSED: 'Tạm dừng',
  COMPLETED: 'Đã hoàn thành',
  ENDED: 'Đã kết thúc',
  ABANDONED: 'Đã bỏ',
  REPLACED: 'Đã thay thế',
  ARCHIVED: 'Đã lưu trữ',
};

export const goalProposalStatusLabels: Record<ProposalStatus, string> = {
  PENDING: 'Đang chờ',
  ACCEPTED: 'Đã chấp nhận',
  REJECTED: 'Đã từ chối',
  CANCELLED: 'Đã hủy',
  EXPIRED: 'Đã hết hạn',
};

export const aiRecommendationStatusLabels: Record<AdjustmentRecommendationStatus, string> = {
  PENDING: 'Đang chờ',
  ACCEPTED: 'Đã chấp nhận',
  REJECTED: 'Đã từ chối',
  EXPIRED: 'Đã hết hạn',
  CANCELLED: 'Đã hủy',
  APPLIED: 'Đã áp dụng',
};

export const unspecified = 'Chưa cung cấp';


const scheduleFieldLabels: Record<string, string> = {
  localStart: 'giờ bắt đầu',
  localEnd: 'giờ kết thúc',
  timezone: 'múi giờ',
  startUtcOffset: 'độ lệch UTC giờ bắt đầu',
  endUtcOffset: 'độ lệch UTC giờ kết thúc',
  appointmentId: 'lịch hẹn',
};

export function scheduleFieldLabel(field: string | null | undefined): string {
  return field ? scheduleFieldLabels[field] ?? 'thông tin buổi tập' : '';
}

/** Keep server details in ApiError.response while showing a stable Vietnamese message. */
export function apiErrorMessage(error: unknown, fallback = 'Có lỗi xảy ra. Vui lòng thử lại.'): string {
  if (!error || typeof error !== 'object' || !('status' in error)) return fallback;
  const response = 'errorResponse' in error ? error.errorResponse : undefined;
  const errorCode = response && typeof response === 'object' && 'errorCode' in response ? response.errorCode : undefined;
  if (errorCode === 'INVALID_CREDENTIALS') return 'Email hoặc mật khẩu không đúng.';
  if (errorCode === 'INVALID_OR_EXPIRED_TOKEN') return 'Mã xác minh không hợp lệ hoặc đã hết hạn.';
  if (errorCode === 'SCANNER_UNAVAILABLE') {
    return 'Hệ thống quét an toàn tệp hiện chưa sẵn sàng. Bạn có thể nộp hồ sơ kèm ghi chú hoặc thử tải tệp lại sau.';
  }
  if (errorCode === 'STORAGE_UNAVAILABLE') {
    return 'Hệ thống lưu trữ tài liệu hiện chưa sẵn sàng. Bạn có thể thử tải tệp lại sau hoặc nộp hồ sơ kèm ghi chú.';
  }
  if (errorCode === 'TRAINER_APPLICATION_ALREADY_ACTIVE') {
    return 'Bạn đã có một hồ sơ xác minh đang chờ xét duyệt.';
  }
  if (errorCode === 'TRAINER_CAPABILITY_UNAVAILABLE') {
    return 'Hồ sơ hoặc vai trò Huấn luyện viên hiện chưa khả dụng.';
  }
  if (errorCode === 'TRAINER_CAPABILITY_REVOKED') {
    return 'Quyền Huấn luyện viên của tài khoản đã bị thu hồi.';
  }
  if (errorCode === 'INVALID_LIFECYCLE_TRANSITION') {
    return 'Trạng thái hồ sơ hiện tại không cho phép thực hiện thao tác này. Vui lòng làm mới trạng thái.';
  }
  switch (error.status) {
    case 0: return 'Không thể kết nối mạng. Vui lòng kiểm tra kết nối Internet.';
    case 400: return 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.';
    case 401: return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
    case 403: return 'Bạn không có quyền thực hiện thao tác này.';
    case 404: return 'Không tìm thấy dữ liệu được yêu cầu.';
    case 409: return 'Dữ liệu đã thay đổi. Vui lòng tải lại và thử lại.';
    case 422: return 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.';
    default: return fallback;
  }
}
