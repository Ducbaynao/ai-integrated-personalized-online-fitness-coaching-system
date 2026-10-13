import { ApiError } from '@/types/auth';
import type { WorkoutScheduleChangeProposalStatus, WorkoutScheduleChangeType } from '@/types/workoutScheduleChangeProposal';
import { formatTrainerScheduleTime } from '@/features/workout-schedule/scheduleDisplay';

export const changeProposalStatusLabels: Record<WorkoutScheduleChangeProposalStatus, string> = {
  PENDING: 'Đang chờ Học viên quyết định',
  ACCEPTED: 'Đã được Học viên chấp nhận',
  REJECTED: 'Đã bị Học viên từ chối',
  WITHDRAWN: 'Đã rút đề xuất',
};

export const studentChangeProposalStatusLabels: Record<WorkoutScheduleChangeProposalStatus, string> = {
  PENDING: 'Đang chờ bạn quyết định',
  ACCEPTED: 'Đã chấp nhận',
  REJECTED: 'Đã từ chối',
  WITHDRAWN: 'Huấn luyện viên đã rút đề xuất',
};

export const changeTypeLabels: Record<WorkoutScheduleChangeType, string> = {
  RESCHEDULE: 'Đề xuất đổi lịch',
  CANCEL: 'Đề xuất hủy buổi tập',
};

export function changeProposalErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Không thể xử lý đề xuất. Vui lòng thử lại.';
  if (error.errorResponse?.errorCode === 'WORKOUT_SCHEDULE_CHANGE_REASON_REQUIRED')
    return 'Buổi tập đã qua giờ bắt đầu. Hãy nhập lý do của bạn để chấp nhận hủy.';
  if (error.errorResponse?.errorCode === 'WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STALE')
    return 'Quyền huấn luyện viên hoặc buổi tập nguồn đã thay đổi. Đề xuất vẫn chờ; hãy xem lại trước khi quyết định hoặc từ chối.';
  if (error.status === 0) return 'Mất kết nối mạng. Lệnh có thể đã hoàn tất; hãy thử lại với đúng yêu cầu cũ.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  if (error.status === 403 || error.status === 404) return 'Đề xuất không tồn tại hoặc bạn không còn quyền xem.';
  if (error.status === 409) return 'Occurrence, đề xuất hoặc quyền đã thay đổi. Hãy tải lại và xác nhận một lệnh mới.';
  if (error.status >= 500) return 'Máy chủ đang gặp sự cố. Lệnh có thể đã hoàn tất; hãy thử lại cùng yêu cầu.';
  return 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.';
}

export function formatChangeTime(value: string, timezone: string | null) {
  return formatTrainerScheduleTime(value, timezone);
}
