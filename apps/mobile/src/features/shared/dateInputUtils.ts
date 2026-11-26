import moment from 'moment-timezone';

export interface DateOnlyParts {
  year: number;
  month: number;
  day: number;
}

const DATE_ONLY_REGEX = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

export function daysInMonth(year: number, month: number): number {
  if (month < 1 || month > 12) return 0;
  if (month === 2) return isLeapYear(year) ? 29 : 28;
  if (month === 4 || month === 6 || month === 9 || month === 11) return 30;
  return 31;
}

/**
 * Strictly parses a calendar YYYY-MM-DD string without converting through Date/UTC.
 */
export function parseDateOnly(value?: string | null): DateOnlyParts | null {
  if (!value) return null;
  const trimmed = value.trim();
  const match = DATE_ONLY_REGEX.exec(trimmed);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const maxDay = daysInMonth(year, month);
  if (maxDay === 0 || day < 1 || day > maxDay) return null;
  return { year, month, day };
}

export function formatDateOnly(parts: DateOnlyParts): string {
  return `${String(parts.year).padStart(4, '0')}-${String(parts.month).padStart(2, '0')}-${String(parts.day).padStart(2, '0')}`;
}

/**
 * Formats a valid YYYY-MM-DD calendar date into Vietnamese DD/MM/YYYY display text.
 */
export function formatVietnameseDateDisplay(value?: string | null): string {
  if (!value || !value.trim()) return '';
  const parts = parseDateOnly(value);
  if (!parts) return value.trim();
  return `${String(parts.day).padStart(2, '0')}/${String(parts.month).padStart(2, '0')}/${String(parts.year).padStart(4, '0')}`;
}

/**
 * Resolves today's YYYY-MM-DD in the supplied IANA timezone or local calendar without UTC day shift.
 */
export function getTodayDateOnly(timezone?: string | null): string {
  const nowMs = moment.now();
  if (timezone && moment.tz.zone(timezone)) {
    return moment.tz(nowMs, timezone).format('YYYY-MM-DD');
  }
  const now = new Date(nowMs);
  return formatDateOnly({
    year: now.getFullYear(),
    month: now.getMonth() + 1,
    day: now.getDate(),
  });
}

export function compareDateOnly(a: string, b: string): number {
  if (a === b) return 0;
  return a < b ? -1 : 1;
}

export function isDateOnlyWithinBounds(
  value: string,
  minDate?: string,
  maxDate?: string
): boolean {
  if (!parseDateOnly(value)) return false;
  if (minDate && parseDateOnly(minDate) && compareDateOnly(value, minDate) < 0) return false;
  if (maxDate && parseDateOnly(maxDate) && compareDateOnly(value, maxDate) > 0) return false;
  return true;
}

/**
 * Checks whether a YYYY-MM-DD date falls on Monday using pure Gregorian calendar arithmetic.
 */
export function isMondayDateOnly(value: string): boolean {
  const parts = parseDateOnly(value);
  if (!parts) return false;
  return new Date(Date.UTC(parts.year, parts.month - 1, parts.day)).getUTCDay() === 1;
}

/**
 * Returns Monday-first (T2..CN) calendar grid cells for the specified year and 1-based month.
 */
export function getCalendarGridDays(year: number, month: number): (number | null)[] {
  const totalDays = daysInMonth(year, month);
  if (totalDays === 0) return [];
  const utcDay = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
  const mondayFirstOffset = (utcDay + 6) % 7;
  const cells: (number | null)[] = Array.from({ length: mondayFirstOffset }, () => null);
  for (let d = 1; d <= totalDays; d += 1) {
    cells.push(d);
  }
  while (cells.length % 7 !== 0) {
    cells.push(null);
  }
  return cells;
}

export function splitLocalDateTime(localValue?: string | null): { date: string; time: string } {
  if (!localValue) return { date: '', time: '' };
  const tIndex = localValue.indexOf('T');
  if (tIndex === -1) {
    return parseDateOnly(localValue)
      ? { date: localValue.trim(), time: '' }
      : { date: '', time: localValue.trim() };
  }
  return {
    date: localValue.slice(0, tIndex),
    time: localValue.slice(tIndex + 1),
  };
}

export function joinLocalDateTime(date: string, time: string): string {
  const d = date.trim();
  const t = time.trim();
  if (!d && !t) return '';
  if (!d) return `T${t}`;
  return `${d}T${t}`;
}

/**
 * Converts a YYYY-MM-DD calendar date into an ISO instant for range filtering.
 * 'start' is inclusive (00:00:00.000 of dateOnly);
 * 'end' is exclusive (00:00:00.000 of the next calendar day, matching SQL `start_at < until`).
 */
export function localDateRangeBoundaryIso(
  dateOnly: string,
  boundary: 'start' | 'end',
  timezone?: string | null
): string | null {
  const parts = parseDateOnly(dateOnly);
  if (!parts) return null;
  if (timezone && moment.tz.zone(timezone)) {
    const startOfSelected = moment.tz(dateOnly, 'YYYY-MM-DD', true, timezone).startOf('day');
    if (!startOfSelected.isValid()) return null;
    const instant = boundary === 'start' ? startOfSelected : startOfSelected.clone().add(1, 'day').startOf('day');
    return instant.toISOString();
  }
  const local =
    boundary === 'start'
      ? new Date(parts.year, parts.month - 1, parts.day, 0, 0, 0, 0)
      : new Date(parts.year, parts.month - 1, parts.day + 1, 0, 0, 0, 0);
  return local.toISOString();
}

export function isoTimestampToDateOnly(iso?: string | null, timezone?: string | null): string {
  if (!iso) return '';
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) return '';
  if (timezone && moment.tz.zone(timezone)) {
    return moment.tz(ms, timezone).format('YYYY-MM-DD');
  }
  const d = new Date(ms);
  return formatDateOnly({
    year: d.getFullYear(),
    month: d.getMonth() + 1,
    day: d.getDate(),
  });
}

/**
 * Updates only the calendar date portion of an ISO timestamp while preserving time-of-day
 * and timezone/offset semantics. Unchanged dates return the original ISO instant untouched.
 */
export function replaceDateInIsoTimestamp(
  currentIso: string,
  nextDateOnly: string,
  timezone?: string | null
): string | null {
  const parts = parseDateOnly(nextDateOnly);
  if (!parts) return null;
  const ms = Date.parse(currentIso);
  if (!Number.isFinite(ms)) return null;

  if (isoTimestampToDateOnly(currentIso, timezone) === formatDateOnly(parts)) {
    return currentIso;
  }

  if (timezone && moment.tz.zone(timezone)) {
    const prev = moment.tz(ms, timezone);
    const targetHour = prev.hour();
    const targetMinute = prev.minute();
    const targetSecond = prev.second();
    const targetMillisecond = prev.millisecond();
    const updated = moment.tz(
      {
        year: parts.year,
        month: parts.month - 1,
        day: parts.day,
        hour: targetHour,
        minute: targetMinute,
        second: targetSecond,
        millisecond: targetMillisecond,
      },
      timezone
    );
    // Reject nonexistent local wall times (e.g. DST spring-forward gap) where Moment shifts hour/minute.
    if (
      !updated.isValid() ||
      updated.year() !== parts.year ||
      updated.month() !== parts.month - 1 ||
      updated.date() !== parts.day ||
      updated.hour() !== targetHour ||
      updated.minute() !== targetMinute ||
      updated.second() !== targetSecond ||
      updated.millisecond() !== targetMillisecond
    ) {
      return null;
    }
    return updated.toISOString();
  }

  const prevLocal = new Date(ms);
  const targetHour = prevLocal.getHours();
  const targetMinute = prevLocal.getMinutes();
  const targetSecond = prevLocal.getSeconds();
  const targetMillisecond = prevLocal.getMilliseconds();
  const updatedLocal = new Date(
    parts.year,
    parts.month - 1,
    parts.day,
    targetHour,
    targetMinute,
    targetSecond,
    targetMillisecond
  );
  if (
    updatedLocal.getFullYear() !== parts.year ||
    updatedLocal.getMonth() !== parts.month - 1 ||
    updatedLocal.getDate() !== parts.day ||
    updatedLocal.getHours() !== targetHour ||
    updatedLocal.getMinutes() !== targetMinute ||
    updatedLocal.getSeconds() !== targetSecond ||
    updatedLocal.getMilliseconds() !== targetMillisecond
  ) {
    return null;
  }
  return updatedLocal.toISOString();
}
