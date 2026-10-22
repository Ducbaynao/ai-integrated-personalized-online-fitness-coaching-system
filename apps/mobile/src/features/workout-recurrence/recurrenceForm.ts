import moment from 'moment-timezone';
import type { WorkoutSession } from '@/types/workoutPlan';
import type { RecurrenceCreate, RecurrenceItem } from '@/types/workoutRecurrence';
import { isMonday, validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';

export interface RecurrenceEntry { session: WorkoutSession; firstLocalStart: string; firstLocalEnd: string }

export function recurrencePreview(anchor: string, timezone: string, entries: RecurrenceEntry[]): { error: string | null; warnings: string[] } {
  if (!isMonday(anchor) || !moment.tz.zone(timezone) || !entries.length) {
    return { error: 'Chọn thứ Hai bắt đầu, múi giờ IANA và ít nhất một buổi.', warnings: [] };
  }
  const warnings: string[] = [];
  const ids = new Set<string>();
  for (const entry of entries) {
    const { session } = entry;
    if (ids.has(session.id)) return { error: 'Một buổi chỉ được chọn một lần.', warnings };
    ids.add(session.id);
    if (!['SELF_ALLOWED', 'COACH_PREFERRED'].includes(session.supervisionRequirement)) {
      return { error: `Buổi ${session.name} chưa được phép tạo tự động.`, warnings };
    }
    const expectedDate = moment(anchor, 'YYYY-MM-DD', true)
      .add(session.weekNumber - 1, 'weeks').add(session.dayNumber - 1, 'days').format('YYYY-MM-DD');
    const start = moment(entry.firstLocalStart, 'YYYY-MM-DDTHH:mm', true);
    const end = moment(entry.firstLocalEnd, 'YYYY-MM-DDTHH:mm', true);
    if (!start.isValid() || !end.isValid() || !start.isBefore(end) || start.format('YYYY-MM-DD') !== expectedDate) {
      return { error: `Kiểm tra ngày/giờ của ${session.name}: bắt đầu phải đúng tọa độ tuần/ngày và kết thúc phải sau bắt đầu.`, warnings };
    }
    const startOffsets = validProposalOffsets(timezone, entry.firstLocalStart);
    const endOffsets = validProposalOffsets(timezone, entry.firstLocalEnd);
    if (startOffsets.length !== 1 || endOffsets.length !== 1) {
      warnings.push(`${session.name}: giờ đầu tiên thiếu hoặc nhập nhằng do DST; kỳ đó sẽ không tự tạo. Học viên có thể xử lý tường minh trong lịch.`);
    } else if (endOffsets[0].instant <= startOffsets[0].instant) {
      return { error: `Giờ kết thúc thực tế của ${session.name} phải sau giờ bắt đầu.`, warnings };
    }
  }
  return { error: null, warnings };
}

export function buildRecurrenceRequest(planId: string, sourcePlanVersionId: string, expectedPlanAggregateVersion: number,
  mondayAnchorDate: string, timezone: string, entries: RecurrenceEntry[], commandKey: string): RecurrenceCreate {
  const { error } = recurrencePreview(mondayAnchorDate, timezone, entries);
  if (error) throw new Error(error);
  const items: RecurrenceItem[] = entries.map(({ session, firstLocalStart, firstLocalEnd }) => ({
    planSessionId: session.id,
    weekNumber: session.weekNumber,
    dayNumber: session.dayNumber,
    sequenceNumber: session.sequenceNumber,
    firstLocalStart,
    firstLocalEnd,
  }));
  return { planId, sourcePlanVersionId, expectedPlanAggregateVersion, mondayAnchorDate, timezone, items, commandKey };
}
