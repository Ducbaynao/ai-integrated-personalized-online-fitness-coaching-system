import { SCHEDULE_ITEM_ERROR_FALLBACK, scheduleItemErrorMessage } from '@/localization/scheduleItemErrors';
import { formatContextFreeInstant } from '@/localization/instantDisplay';
import { UNNAMED_NUTRIENT_LABEL, nutrientName } from '@/localization/nutritionLabels';
import { CONTINUITY_STATE_LABELS, TREND_DIRECTION_LABELS } from '@/services/aiProgressSummaryApi';
import { continuityStateLabel, trendLabel } from '@/localization/progressLabels';

describe('scheduleItemErrorMessage', () => {
  it('gives every Backend item-level code its own copy', () => {
    const codes = [
      'WORKOUT_SCHEDULE_DUPLICATE',
      'WORKOUT_SCHEDULE_OVERLAP',
      'WORKOUT_SCHEDULE_APPOINTMENT_NOT_QUALIFYING',
      'WORKOUT_SCHEDULE_SOURCE_MISMATCH',
      'WORKOUT_SCHEDULE_COACH_REQUIRED_UNSUPPORTED',
    ];
    const messages = codes.map(scheduleItemErrorMessage);
    expect(new Set(messages).size).toBe(codes.length);
    messages.forEach((m) => {
      expect(m).not.toBe(SCHEDULE_ITEM_ERROR_FALLBACK);
      expect(m).not.toMatch(/[A-Z]{3,}_/);
    });
  });

  it('uses the neutral fallback for VALIDATION_FAILED, unknown, non-string and prototype-like codes', () => {
    for (const code of ['VALIDATION_FAILED', 'SOMETHING_NEW', '', undefined, null, 42, { code: 'X' }, 'constructor', 'toString']) {
      expect(scheduleItemErrorMessage(code)).toBe(SCHEDULE_ITEM_ERROR_FALLBACK);
    }
  });
});

describe('formatContextFreeInstant', () => {
  it('formats an exact instant in UTC with an explicit UTC label', () => {
    const text = formatContextFreeInstant('2026-11-01T08:05:00Z');
    expect(text).toMatch(/· UTC$/);
    expect(text).toContain('08:05');
    expect(text).toContain('2026');
  });

  it('does not shift the wall clock to Vietnam/device time', () => {
    // 23:30 UTC is 06:30 the next day in Asia/Ho_Chi_Minh; the label must keep the UTC time.
    const text = formatContextFreeInstant('2026-11-01T23:30:00Z');
    expect(text).toContain('23:30');
    expect(text).not.toContain('06:30');
  });

  it('honours an explicit offset by converting it to UTC', () => {
    expect(formatContextFreeInstant('2026-11-01T15:05:00+07:00')).toContain('08:05');
    expect(formatContextFreeInstant('2026-11-01T08:05:00.123456Z')).toContain('08:05');
  });

  it('returns null instead of guessing for missing, date-only, offset-less or invalid values', () => {
    for (const value of [undefined, null, '', '2026-11-01', '2026-11-01T08:05:00', 'not a date', '2026-13-45T99:99:00Z', 1730448300000]) {
      expect(formatContextFreeInstant(value)).toBeNull();
    }
  });
});

describe('nutrientName', () => {
  it('names the V39 system nutrients in Vietnamese by code, without a unit', () => {
    expect(nutrientName({ code: 'B07_ENERGY_KCAL', name: 'Energy' })).toBe('Năng lượng');
    expect(nutrientName({ code: 'B07_FAT_G', name: 'Fat' })).toBe('Chất béo');
    for (const code of ['B07_ENERGY_KCAL', 'B07_PROTEIN_G', 'B07_CARBOHYDRATE_G', 'B07_FAT_G']) {
      expect(nutrientName({ code, name: 'x' })).not.toMatch(/\(|kcal|\bg\b/);
    }
  });

  it('keeps other nutrient names verbatim and falls back neutrally when unnamed', () => {
    expect(nutrientName({ code: 'CUSTOM_FIBER', name: 'Chất xơ hòa tan' })).toBe('Chất xơ hòa tan');
    expect(nutrientName({ code: 'ENERGY', name: 'Energy' })).toBe('Energy');
    expect(nutrientName({ code: 'X', name: '  ' })).toBe(UNNAMED_NUTRIENT_LABEL);
    expect(nutrientName(undefined)).toBe(UNNAMED_NUTRIENT_LABEL);
    expect(nutrientName(undefined, 'chất dinh dưỡng')).toBe('chất dinh dưỡng');
    expect(nutrientName({ code: 'constructor', name: 'Tên riêng' })).toBe('Tên riêng');
  });
});

describe('aiProgressSummaryApi label exports share one source with the Progress screens', () => {
  it('continuity labels equal continuityStateLabel()', () => {
    for (const state of Object.keys(CONTINUITY_STATE_LABELS)) {
      expect(CONTINUITY_STATE_LABELS[state]).toBe(continuityStateLabel(state));
    }
    expect(Object.keys(CONTINUITY_STATE_LABELS).sort()).toEqual(
      ['CONFIRMED_NEW', 'PROLONGED_INACTIVITY', 'RECENT_ACTIVITY_OBSERVED', 'RESTRICTED_HISTORY', 'UNKNOWN_HISTORY']
    );
  });

  it('trend labels equal trendLabel(), so STABLE is never "Ổn định"', () => {
    for (const code of ['INCREASED', 'DECREASED', 'STABLE', 'INSUFFICIENT_DATA']) {
      const { label, symbol } = trendLabel(code);
      expect(TREND_DIRECTION_LABELS[code]).toEqual({ label, symbol });
    }
    expect(TREND_DIRECTION_LABELS.STABLE.label).not.toMatch(/ổn định/i);
  });
});
