import { ApiError } from '@/types/auth';
import type { WorkoutScheduleChangeDestination } from '@/types/workoutSchedule';
import { validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';

export function validateScheduleDestination(destination: WorkoutScheduleChangeDestination): string | null {
  if (!destination.timezone) return 'Hãy chọn và xác nhận múi giờ IANA cho lịch mới.';
  const starts = validProposalOffsets(destination.timezone, destination.localStart);
  const ends = validProposalOffsets(destination.timezone, destination.localEnd);
  if (!starts.length || !ends.length) return 'Giờ địa phương không tồn tại trong múi giờ đã chọn hoặc sai định dạng YYYY-MM-DDTHH:mm.';
  const start = starts.find((item) => item.offset === destination.startUtcOffset);
  const end = ends.find((item) => item.offset === destination.endUtcOffset);
  if (!start || !end) return 'Chọn rõ UTC offset hợp lệ cho cả giờ bắt đầu và kết thúc.';
  if (end.instant <= start.instant) return 'Giờ kết thúc phải sau giờ bắt đầu.';
  const now = Date.now();
  if (start.instant <= now || start.instant > now + 90 * 86_400_000) return 'Giờ bắt đầu mới phải ở tương lai và trong 90 ngày tới. Máy chủ sẽ kiểm tra lại.';
  return null;
}

const messages: Record<string, string> = {
  WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT: 'Lịch tập đã thay đổi. Hãy xem lại dữ liệu mới trước khi thao tác.',
  WORKOUT_SCHEDULE_OCCURRENCE_SEALED: 'Buổi tập đã bắt đầu và không thể đổi hoặc hủy lịch.',
  WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE: 'Trạng thái buổi tập không còn cho phép đổi hoặc hủy lịch.',
  WORKOUT_SCHEDULE_OCCURRENCE_START_PASSED: 'Giờ bắt đầu hiện tại đã qua; không thể đổi lịch.',
  WORKOUT_SCHEDULE_APPOINTMENT_LINKED: 'Buổi tập có cuộc hẹn liên kết nên chưa thể đổi hoặc hủy lịch.',
  WORKOUT_SCHEDULE_LEGACY_SOURCE_UNAVAILABLE: 'Bản ghi cũ thiếu nguồn cần thiết để đổi lịch.',
  WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED: 'Buổi tập cần huấn luyện viên; đổi lịch chưa được hỗ trợ.',
  WORKOUT_SCHEDULE_DUPLICATE: 'Buổi tập tại thời điểm này đã tồn tại.',
  WORKOUT_SCHEDULE_OVERLAP: 'Lịch mới trùng thời gian với buổi tập khác.',
  WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN: 'Có buổi tập hiện hành chưa rõ giờ kết thúc. Chưa thể tạo lịch mới.',
  WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT: 'Lệnh này không còn khớp với yêu cầu ban đầu. Hãy xem lại lịch.',
  WORKOUT_SCHEDULE_CHANGE_REASON_REQUIRED: 'Hủy buổi tập đã qua giờ bắt đầu cần có lý do.',
  WORKOUT_SCHEDULE_DST_TIME_NONEXISTENT: 'Giờ đã chọn không tồn tại trong múi giờ này.',
  WORKOUT_SCHEDULE_DST_OFFSET_REQUIRED: 'Giờ bị lặp khi đổi DST; hãy chọn UTC offset.',
  WORKOUT_SCHEDULE_DST_OFFSET_INVALID: 'UTC offset không hợp lệ với giờ và múi giờ đã chọn.',
  WORKOUT_SCHEDULE_WINDOW_INVALID: 'Giờ bắt đầu mới phải ở tương lai và trong 90 ngày tới.',
};

export function scheduleChangeErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Không thể hoàn tất thao tác. Vui lòng thử lại.';
  const code = error.errorResponse?.errorCode;
  if (code && messages[code]) return messages[code];
  if (error.status === 0) return 'Mất kết nối mạng. Có thể lệnh đã hoàn tất; hãy thử lại với cùng yêu cầu.';
  if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
  if (error.status === 403 || error.status === 404) return 'Bạn không còn quyền xem hoặc thay đổi buổi tập này.';
  if (error.status === 409) return 'Lịch hoặc quyền đã thay đổi. Hãy tải lại và xác nhận lại.';
  if (error.status >= 500) return 'Máy chủ đang gặp sự cố. Có thể lệnh đã hoàn tất; hãy thử lại cùng yêu cầu.';
  return 'Thông tin chưa hợp lệ. Vui lòng kiểm tra lại.';
}
