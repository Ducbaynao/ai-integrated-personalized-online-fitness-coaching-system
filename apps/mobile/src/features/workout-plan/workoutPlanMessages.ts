import { ApiError } from '@/types/auth';
import { WorkoutExerciseState, WorkoutPlanOwnerType, WorkoutPlanStatus } from '@/types/workoutPlan';

export const workoutStatusLabels: Record<WorkoutPlanStatus, string> = { DRAFT: 'Bản nháp', ACTIVE: 'Đang áp dụng', PAUSED: 'Tạm dừng', COMPLETED: 'Hoàn thành', ARCHIVED: 'Đã lưu trữ' };
export const workoutOwnerLabels: Record<WorkoutPlanOwnerType, string> = { STUDENT: 'Học viên tạo', TRAINER: 'Huấn luyện viên bàn giao' };
export const workoutExerciseStateLabels: Record<WorkoutExerciseState, string> = { ACTIVE: 'Đang khả dụng', ARCHIVED: 'Đã lưu trữ', UNAVAILABLE: 'Không còn khả dụng' };

const messages: Record<string, string> = {
  WORKOUT_PLAN_NOT_FOUND: 'Kế hoạch không tồn tại hoặc bạn không còn quyền xem.',
  WORKOUT_PLAN_VERSION_NOT_FOUND: 'Phiên bản không tồn tại hoặc bạn không còn quyền xem.',
  PLANNED_WORKOUT_NOT_FOUND: 'Buổi tập dự kiến không tồn tại hoặc không còn khả dụng.',
  WORKOUT_PLAN_ACCESS_DENIED: 'Bạn không có quyền thực hiện thao tác này trong kỳ huấn luyện hiện tại.',
  COACHING_PERIOD_REQUIRED: 'Chưa có kỳ huấn luyện hiện tại phù hợp cho thao tác này.',
  WORKOUT_PLAN_LIFECYCLE_CONFLICT: 'Trạng thái kế hoạch đã thay đổi hoặc không cho phép thao tác này.',
  ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS: 'Bạn đã có một kế hoạch đang áp dụng. Hãy chuyển trạng thái kế hoạch đó trước.',
  WORKOUT_PLAN_VERSION_CONFLICT: 'Kế hoạch đã được cập nhật ở nơi khác. Hãy xem lại dữ liệu mới nhất trước khi thử lại.',
  PLANNED_WORKOUT_VERSION_CONFLICT: 'Buổi tập dự kiến đã được cập nhật ở nơi khác.',
  WORKOUT_PLAN_IDEMPOTENCY_CONFLICT: 'Yêu cầu lặp lại không còn khớp với thao tác ban đầu.',
  WORKOUT_PLAN_IMMUTABLE: 'Phiên bản đã phát hành là bất biến. Hãy tạo phiên bản mới cho thay đổi chiến lược.',
  WORKOUT_PLAN_SUCCESSOR_REQUIRED: 'Hãy tạo một kế hoạch kế nhiệm do Học viên sở hữu để tiếp tục chỉnh sửa.',
  WORKOUT_PLAN_EXERCISE_UNAVAILABLE: 'Một biến thể bài tập đã chọn không còn khả dụng.',
  WORKOUT_PLAN_EFFECTIVE_TIME_CONFLICT: 'Mốc hiệu lực của phiên bản đã xung đột. Hãy tải lại kế hoạch.',
  DATA_SHARING_ACCESS_LEVEL_INSUFFICIENT: 'Mức quyền hiện tại không đủ cho thao tác này.',
  TRAINER_NOT_ELIGIBLE: 'Huấn luyện viên hiện không đủ điều kiện cho thao tác này.',
  ACCESS_DENIED: 'Bạn không có quyền thực hiện thao tác này.',
  VALIDATION_FAILED: 'Thông tin kế hoạch chưa hợp lệ. Vui lòng kiểm tra lại.',
};

export function workoutPlanErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Đã xảy ra lỗi. Vui lòng thử lại.';
  const code = error.errorResponse?.errorCode;
  if (code && messages[code]) return messages[code];
  if (error.status === 0) return 'Không thể kết nối mạng. Hãy kiểm tra kết nối và thử lại.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  if (error.status === 403) return 'Bạn không có quyền thực hiện thao tác này.';
  if (error.status === 404) return 'Nội dung không tồn tại hoặc bạn không còn quyền xem.';
  if (error.status >= 500) return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
  return 'Không thể hoàn tất thao tác. Vui lòng thử lại.';
}
export const isCurrentPlanMissing = (error: unknown) => error instanceof ApiError && error.status === 404;
export const isWorkoutStaleConflict = (error: unknown) => error instanceof ApiError && error.status === 409 && ['WORKOUT_PLAN_VERSION_CONFLICT', 'WORKOUT_PLAN_LIFECYCLE_CONFLICT', 'WORKOUT_PLAN_EFFECTIVE_TIME_CONFLICT'].includes(error.errorResponse?.errorCode ?? '');

export function isWorkoutAuthorityLoss(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 403 || error.status === 404);
}
