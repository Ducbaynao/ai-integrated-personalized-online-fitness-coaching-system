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
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!/T\d{2}:\d{2}/.test(trimmed) || !/(Z|[+-]\d{2}:?\d{2})$/i.test(trimmed)) return null;
  const ms = Date.parse(trimmed);
  if (!Number.isFinite(ms)) return null;
  const formatted = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
    .format(new Date(ms));
  return `${formatted} · UTC`;
}
