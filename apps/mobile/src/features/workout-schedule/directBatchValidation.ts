import moment from 'moment-timezone';
import type { WorkoutSession } from '@/types/workoutPlan';
import { isInSessionWeek, isMonday, validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';

export interface DirectBatchEntry {
  session: WorkoutSession;
  localStart: string;
  localEnd: string;
  startOffset: string;
  endOffset: string;
}

const DAY = 86_400_000;

export function suggestedSessionDate(anchor: string, session: WorkoutSession): string | null {
  if (!isMonday(anchor)) return null;
  return moment(anchor, 'YYYY-MM-DD', true).add(session.weekNumber - 1, 'weeks').add(session.dayNumber - 1, 'days').format('YYYY-MM-DD');
}

export function validateDirectBatch(anchor: string, timezone: string, entries: DirectBatchEntry[], now?: number): string | null {
  if (!timezone || !isMonday(anchor) || entries.length === 0)
    return 'Chọn múi giờ IANA, ngày thứ Hai đầu tuần và ít nhất một buổi tập.';
  const sessions = new Set<string>();
  const intervals: { from: number; until: number }[] = [];
  for (const entry of entries) {
    const { session } = entry;
    if (sessions.has(session.id)) return 'Một buổi chỉ được chọn một lần trong cùng đợt lịch.';
    sessions.add(session.id);
    if (!['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement))
      return `Buổi ${session.name} chưa được hỗ trợ để tự lên lịch.`;
    if (!isInSessionWeek(anchor, session.weekNumber, entry.localStart))
      return `Ngày bắt đầu của ${session.name} phải nằm trong tuần ${session.weekNumber} tính từ thứ Hai đã chọn.`;
    const start = validProposalOffsets(timezone, entry.localStart).find((choice) => choice.offset === entry.startOffset);
    const end = validProposalOffsets(timezone, entry.localEnd).find((choice) => choice.offset === entry.endOffset);
    if (!start || !end) return `Chọn thời gian và UTC offset hợp lệ cho ${session.name}; giờ DST không tồn tại không thể dùng.`;
    if (end.instant <= start.instant) return `Giờ kết thúc của ${session.name} phải sau giờ bắt đầu.`;
    if (now !== undefined && (start.instant <= now || start.instant > now + 90 * DAY))
      return `Giờ bắt đầu của ${session.name} phải ở tương lai và không quá 90 ngày (máy chủ sẽ kiểm tra lại).`;
    if (intervals.some((other) => start.instant < other.until && other.from < end.instant))
      return 'Các buổi trong cùng đợt lịch không được chồng thời gian.';
    intervals.push({ from: start.instant, until: end.instant });
  }
  return null;
}
