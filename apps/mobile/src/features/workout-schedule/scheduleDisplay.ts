import type { PlannedWorkoutStatus } from '@/types/workoutSchedule';

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
    return `${value} · ${preferred}${timezone ? '' : profileTimezone ? ' (múi giờ hồ sơ)' : ' (không có múi giờ hồ sơ)'}`;
  } catch {
    const value = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date(instant));
    return `${value} · UTC (không xác định được múi giờ)`;
  }
}

export function formatTrainerScheduleTime(instant: string, timezone: string | null) {
  return timezone ? formatScheduleTime(instant, timezone)
    : `${formatScheduleTime(instant, 'UTC')} (múi giờ của bản ghi cũ chưa xác định)`;
}
