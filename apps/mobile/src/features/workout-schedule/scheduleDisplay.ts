import type { PlannedWorkoutStatus } from '@/types/workoutSchedule';
import { formatTimezoneDisplay } from '@/features/shared/timezoneUtils';
import { parseExactInstant } from '@/localization/instantDisplay';

const DAY = 24 * 60 * 60 * 1000;
const WINDOW_DAYS = 30;

export const plannedWorkoutStatusLabels: Record<PlannedWorkoutStatus, string> = {
  SCHEDULED: 'Đã lên lịch', COMPLETED: 'Đã hoàn thành', ATTEMPTED: 'Đã thực hiện một phần',
  MISSED: 'Đã lỡ', SKIPPED: 'Đã bỏ qua', RESCHEDULED: 'Đã đổi lịch', CANCELLED: 'Đã hủy',
};

export function calendarWindow(anchor: number, offset: number) {
  const from = anchor + offset * WINDOW_DAYS * DAY;
  return { from: new Date(from).toISOString(), until: new Date(from + WINDOW_DAYS * DAY).toISOString() };
}

export function formatScheduleTime(instant: string, timezone: string | null, profileTimezone?: string | null) {
  const preferred = timezone || profileTimezone || 'UTC';
  try {
    const value = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: preferred }).format(new Date(instant));
    return `${value} · ${formatTimezoneDisplay(preferred, 'Chưa có múi giờ', instant)}${timezone ? '' : profileTimezone ? ' (múi giờ hồ sơ)' : ' (không có múi giờ hồ sơ)'}`;
  } catch {
    const value = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date(instant));
    return `${value} · UTC (không xác định được múi giờ)`;
  }
}

export function formatTrainerScheduleTime(instant: string, timezone: string | null) {
  return timezone ? formatScheduleTime(instant, timezone)
    : `${formatScheduleTime(instant, 'UTC')} (múi giờ của bản ghi cũ chưa xác định)`;
}

/**
 * Shows an instant that belongs to a saved record (planned workout, appointment) in that record's own
 * timezone with an explicit label, never in the phone's timezone. A record without a saved timezone is
 * shown in UTC and marked as such rather than guessed. Returns null when the value is not an exact
 * instant, so callers show "unknown" instead of a made-up time (or crashing on an invalid date).
 */
export function formatRecordedScheduleTime(instant: unknown, timezone: string | null | undefined): string | null {
  if (parseExactInstant(instant) === null) return null;
  return formatTrainerScheduleTime(instant as string, timezone ?? null);
}
