import { ApiError } from '@/types/auth';
import {
  WorkoutExecutionSetCompletionStatus,
  WorkoutExecutionSetType,
  WorkoutExecutionStatus,
  WorkoutExecutionSupervisionRequirement,
} from '@/types/workoutExecution';

export const workoutExecutionStatusLabels: Record<WorkoutExecutionStatus, string> = {
  IN_PROGRESS: 'Đang tập',
  COMPLETED: 'Đã hoàn thành',
  PARTIALLY_COMPLETED: 'Hoàn thành một phần',
  ABORTED: 'Đã hủy',
};

export const workoutSetStatusLabels: Record<WorkoutExecutionSetCompletionStatus, string> = {
  PLANNED: 'Chưa thực hiện',
  COMPLETED: 'Hoàn thành',
  SKIPPED: 'Bỏ qua',
  FAILED: 'Không hoàn thành',
};

export const workoutSetTypeLabels: Record<WorkoutExecutionSetType, string> = {
  WARMUP: 'Khởi động',
  WORKING: 'Hiệp chính',
  DROP: 'Drop set',
  FAILURE: 'Đến ngưỡng',
  AMRAP: 'Tối đa số lần',
  COOLDOWN: 'Thả lỏng',
};

export const supervisionLabels: Record<WorkoutExecutionSupervisionRequirement, string> = {
  SELF_PERFORMABLE: 'Có thể tự tập',
  COACH_OPTIONAL: 'Huấn luyện viên không bắt buộc',
  COACH_REQUIRED: 'Cần Huấn luyện viên giám sát',
};

const messages: Record<string, string> = {
  WORKOUT_EXECUTION_NOT_FOUND: 'Buổi tập không tồn tại hoặc bạn không còn quyền xem.',
  WORKOUT_EXECUTION_ACCESS_DENIED: 'Bạn không có quyền truy cập buổi tập này.',
  ACTIVE_WORKOUT_EXECUTION_EXISTS: 'Bạn đang có một buổi tập khác chưa kết thúc.',
  PLANNED_WORKOUT_ALREADY_STARTED: 'Buổi tập dự kiến này đã được bắt đầu.',
  PLANNED_WORKOUT_NOT_STARTABLE: 'Buổi tập dự kiến hiện không thể bắt đầu.',
  WORKOUT_EXECUTION_LIFECYCLE_CONFLICT: 'Trạng thái buổi tập đã thay đổi. Hãy tải lại dữ liệu.',
  WORKOUT_EXECUTION_VERSION_CONFLICT: 'Buổi tập đã được cập nhật ở nơi khác. Hãy tải lại trước khi tiếp tục.',
  WORKOUT_EXECUTION_IDEMPOTENCY_CONFLICT: 'Yêu cầu lặp lại không còn khớp với thao tác ban đầu.',
  WORKOUT_SET_IDENTITY_CONFLICT: 'Hiệp tập này đã được dùng cho một nội dung khác. Hãy tải lại buổi tập.',
  WORKOUT_EXECUTION_TERMINAL: 'Buổi tập đã kết thúc và chỉ còn có thể xem.',
  WORKOUT_EXECUTION_BASELINE_INVALID: 'Dữ liệu gốc của buổi tập không còn hợp lệ.',
  VALIDATION_FAILED: 'Thông tin buổi tập chưa hợp lệ. Vui lòng kiểm tra lại.',
};

export function workoutExecutionErrorMessage(error: unknown): string {
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

export const isCurrentExecutionMissing = (error: unknown) =>
  error instanceof ApiError && error.status === 404;

export const isWorkoutExecutionStale = (error: unknown) =>
  error instanceof ApiError &&
  error.status === 409 &&
  [
    'WORKOUT_EXECUTION_VERSION_CONFLICT',
    'WORKOUT_EXECUTION_LIFECYCLE_CONFLICT',
    'WORKOUT_EXECUTION_TERMINAL',
  ].includes(error.errorResponse?.errorCode ?? '');
