import { ApiError } from '@/types/auth';
import { CoachingStatus, DataAccessLevel, DataScope, PermissionState } from '@/types/coaching';

export const coachingStatusLabels: Record<CoachingStatus, string> = {
  PENDING: 'Đang chờ', ACTIVE: 'Đang huấn luyện', PAUSED: 'Đang tạm dừng',
  ENDED: 'Đã kết thúc', REJECTED: 'Đã từ chối', CANCELLED: 'Đã hủy',
};
export const accessLevelLabels: Record<DataAccessLevel, string> = { VIEW: 'Xem', CONTRIBUTE: 'Đóng góp', MANAGE: 'Quản lý' };
export const permissionStateLabels: Record<PermissionState, string> = {
  NOT_CONFIGURED: 'Chưa thiết lập', ALLOWED: 'Đã chia sẻ', DENIED: 'Không chia sẻ',
  EXPIRED: 'Đã hết hạn', REVOKED: 'Đã thu hồi',
};
export const dataScopeLabels: Record<DataScope, string> = {
  FITNESS_GOAL: 'Mục tiêu thể chất', WORKOUT_PLAN: 'Kế hoạch tập luyện',
  WORKOUT_HISTORY: 'Lịch sử buổi tập', WORKOUT_PLAN_HISTORY: 'Lịch sử kế hoạch',
  BODY_METRICS: 'Chỉ số cơ thể', PROGRESS_PHOTOS: 'Ảnh tiến trình',
  NUTRITION_LOGS: 'Nhật ký dinh dưỡng', AI_RECOMMENDATIONS: 'Đề xuất AI',
};

const codeMessages: Record<string, string> = {
  COACHING_COUNTERPARTY_NOT_FOUND: 'Không thể tìm thấy hoặc người dùng này hiện không khả dụng.',
  COACHING_RELATIONSHIP_NOT_FOUND: 'Quan hệ huấn luyện không tồn tại hoặc bạn không còn quyền xem.',
  COACHING_RESUME_REQUEST_NOT_FOUND: 'Yêu cầu tiếp tục không tồn tại hoặc bạn không còn quyền xem.',
  COACHING_COUNTERPARTY_REQUIRED: 'Chỉ người nhận yêu cầu mới có thể thực hiện quyết định này.',
  COACHING_INITIATOR_REQUIRED: 'Chỉ người đã gửi yêu cầu mới có thể hủy.',
  COACHING_IDEMPOTENCY_CONFLICT: 'Thao tác này không còn khớp với lần gửi trước. Vui lòng tải lại trạng thái mới nhất.',
  COACHING_REQUEST_ALREADY_PENDING: 'Yêu cầu giữa hai bên đã tồn tại. Dữ liệu mới nhất đang được tải lại.',
  COACHING_STUDENT_ALREADY_ASSIGNED: 'Học viên đang có một quan hệ huấn luyện hiện tại.',
  COACHING_RELATIONSHIP_STATE_CONFLICT: 'Trạng thái quan hệ đã thay đổi. Vui lòng xem dữ liệu mới nhất.',
  COACHING_VERSION_CONFLICT: 'Dữ liệu đã được cập nhật ở nơi khác. Vui lòng kiểm tra lại.',
  COACHING_RESUME_REQUEST_VERSION_CONFLICT: 'Yêu cầu tiếp tục đã thay đổi. Vui lòng kiểm tra lại.',
  COACHING_RESUME_REQUEST_STATE_CONFLICT: 'Yêu cầu tiếp tục không còn chờ xử lý.',
  COACHING_RESUME_ALREADY_PENDING: 'Đã có một yêu cầu tiếp tục đang chờ xử lý.',
  COACHING_PERIOD_CONFLICT: 'Thời kỳ huấn luyện đã thay đổi. Vui lòng tải lại.',
  DATA_SHARING_PERMISSION_CONFLICT: 'Quyền chia sẻ đã thay đổi. Vui lòng kiểm tra lại trước khi tiếp tục.',
  DATA_SHARING_PERMISSION_NOT_FOUND: 'Quyền chia sẻ không tồn tại hoặc không còn khả dụng.',
  DATA_SHARING_ACCESS_LEVEL_INSUFFICIENT: 'Mức quyền hiện tại không đủ cho thao tác này.',
  TRAINER_NOT_ELIGIBLE: 'Huấn luyện viên hiện không đủ điều kiện cho thao tác này.',
  ACCESS_DENIED: 'Bạn không có quyền thực hiện thao tác này.',
  VALIDATION_FAILED: 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.',
};

export function coachingErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Đã xảy ra lỗi. Vui lòng thử lại.';
  const code = error.errorResponse?.errorCode;
  if (code && codeMessages[code]) return codeMessages[code];
  if (error.status === 0) return 'Không thể kết nối mạng. Vui lòng kiểm tra kết nối và thử lại.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  if (error.status === 403) return 'Bạn không có quyền thực hiện thao tác này.';
  if (error.status >= 500) return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
  return 'Không thể hoàn tất thao tác. Vui lòng thử lại.';
}

export function isStaleCoachingError(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status !== 409) return false;
  return ['COACHING_VERSION_CONFLICT', 'COACHING_RELATIONSHIP_STATE_CONFLICT',
    'COACHING_RESUME_REQUEST_VERSION_CONFLICT', 'COACHING_RESUME_REQUEST_STATE_CONFLICT',
    'DATA_SHARING_PERMISSION_CONFLICT', 'COACHING_PERIOD_CONFLICT'].includes(error.errorResponse?.errorCode ?? '');
}
