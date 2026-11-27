import { DEFAULT_APP_TIMEZONE } from '@/features/shared/timezoneUtils';

/**
 * Display for an exact instant (Backend `Instant`, e.g. "2026-11-01T08:00:00Z") that carries no timezone
 * context of its own, such as a progress photo's takenAt or an AI run's completedAt.
 *
 * The record does not say which local clock it belongs to, so it is shown in UTC with an explicit "UTC"
 * label — the same convention as formatProposalInstant's fallback — instead of being reinterpreted in
 * Vietnam, account or device time.
 *
 * Returns null when the value is missing, not a string, date-only, lacks an explicit offset/Z, or does not
 * parse; callers show their own "unknown" copy rather than a guessed time.
 */
export function formatContextFreeInstant(value: unknown): string | null {
  const ms = parseExactInstant(value);
  if (ms === null) return null;
  const formatted = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
    .format(new Date(ms));
  return `${formatted} · UTC`;
}

/**
 * Timezone for the ordinary moments the app shows as plain instants (a chat message, an in-app
 * notification, "updated at"). It is fixed, never read from the phone, so the same instant reads the
 * same on every device. Business moments that carry their own saved timezone or context (an
 * appointment, a planned workout, a review window) must NOT use it: they keep that context.
 */
export const ORDINARY_INSTANT_TIMEZONE = DEFAULT_APP_TIMEZONE;

/**
 * Parses only an exact instant: a string with a time of day and an explicit `Z` or offset. Missing,
 * non-string, date-only, offset-less (a local time is not an instant) and unparsable values give
 * `null`, so callers show their own "unknown" copy instead of a guessed time.
 */
export function parseExactInstant(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!/T\d{2}:\d{2}/.test(trimmed) || !/(Z|[+-]\d{2}:?\d{2})$/i.test(trimmed)) return null;
  const ms = Date.parse(trimmed);
  return Number.isFinite(ms) ? ms : null;
}

/**
 * Date and time of an ordinary instant in Vietnam time (`Asia/Ho_Chi_Minh`), e.g. "27 thg 11, 2026 10:30".
 * Returns null when the value is not an exact instant (see `parseExactInstant`).
 */
export function formatInstantVietnam(value: unknown): string | null {
  const ms = parseExactInstant(value);
  if (ms === null) return null;
  return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: ORDINARY_INSTANT_TIMEZONE })
    .format(new Date(ms));
}

/** Calendar day of an ordinary instant in Vietnam time. Returns null for anything but an exact instant. */
export function formatInstantDateVietnam(value: unknown): string | null {
  const ms = parseExactInstant(value);
  if (ms === null) return null;
  return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeZone: ORDINARY_INSTANT_TIMEZONE })
    .format(new Date(ms));
}
