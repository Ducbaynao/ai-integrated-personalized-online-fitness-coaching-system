import { ApiError } from '@/types/auth';
import type { WorkoutScheduleProposalStatus } from '@/types/workoutScheduleProposal';

export const proposalStatusLabels: Record<WorkoutScheduleProposalStatus, string> = {
  PENDING: 'Đang chờ bạn quyết định',
  ACCEPTED: 'Đã chấp nhận',
  REJECTED: 'Đã từ chối',
  WITHDRAWN: 'Huấn luyện viên đã rút đề xuất',
};

export function formatProposalLocal(value: string, offset: string, timezone: string): string {
  return `${value.replace('T', ' ')} (UTC${offset}) · ${timezone}`;
}

export function formatProposalInstant(value: string, timezone: string): string {
  try {
    return `${new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: timezone }).format(new Date(value))} · ${timezone}`;
  } catch {
    return `${new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date(value))} · UTC`;
  }
}

export function proposalErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Đã xảy ra lỗi. Vui lòng thử lại.';
  if (error.status === 0) return 'Không có kết nối mạng. Hãy kiểm tra kết nối rồi thử lại cùng yêu cầu.';
  if (error.status === 403 || error.status === 404)
    return 'Đề xuất không tồn tại hoặc bạn không còn quyền xem.';
  if (error.status === 409) return 'Đề xuất hoặc lịch tập đã thay đổi. Hãy xem dữ liệu mới và quyết định lại.';
  if (error.status >= 500) return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
  return 'Không thể xử lý đề xuất này. Vui lòng kiểm tra và thử lại.';
}
