import {
  UNNAMED_METRIC_LABEL,
  continuityExplanation,
  continuityStateLabel,
  daysSinceLastWorkoutText,
  metricLabel,
  photoViewLabel,
  progressSummaryResultTypeLabel,
  sectionNotice,
  trendLabel,
  unitLabel,
  unitTypeLabel,
  valueFieldLabel,
  withUnit,
} from '@/localization/progressLabels';
import { aiRequestTypeLabel, aiRunStatusLabel } from '@/localization/aiRunLabels';

const CONTINUITY_STATES = [
  'CONFIRMED_NEW',
  'PROLONGED_INACTIVITY',
  'RECENT_ACTIVITY_OBSERVED',
  'UNKNOWN_HISTORY',
  'RESTRICTED_HISTORY',
];

describe('trendLabel', () => {
  it('never presents a missing or unrecognised direction as stable/unchanged', () => {
    const stable = trendLabel('STABLE');
    for (const direction of [undefined, null, '', 'SIDEWAYS', 'stable']) {
      const trend = trendLabel(direction);
      expect(trend.tone).toBe('unknown');
      expect(trend.label).not.toBe(stable.label);
      expect(trend.label).not.toMatch(/ổn định/i);
    }
  });

  it('keeps STABLE (zero delta), INSUFFICIENT_DATA and unknown distinguishable', () => {
    const labels = new Set([trendLabel('STABLE').label, trendLabel('INSUFFICIENT_DATA').label, trendLabel(undefined).label]);
    expect(labels.size).toBe(3);
    expect(trendLabel('STABLE').tone).toBe('flat');
    expect(trendLabel('INCREASED').tone).toBe('up');
    expect(trendLabel('DECREASED').tone).toBe('down');
  });
});

describe('sectionNotice', () => {
  it('does not show RESTRICTED as "no data"', () => {
    for (const section of ['measurement', 'workout'] as const) {
      const restricted = sectionNotice(section, 'RESTRICTED', false);
      const noData = sectionNotice(section, 'NO_DATA', false);
      expect(restricted).toBeTruthy();
      expect(noData).toBeTruthy();
      expect(restricted).not.toBe(noData);
      expect(restricted).toMatch(/phạm vi quyền xem/);
      expect(restricted).not.toMatch(/Chưa có/);
    }
  });

  it('keeps RESTRICTED even when rows happen to be present', () => {
    expect(sectionNotice('measurement', 'RESTRICTED', true)).toMatch(/phạm vi quyền xem/);
  });

  it('separates available-but-not-comparable measurements from no data', () => {
    const partial = sectionNotice('measurement', 'AVAILABLE', false);
    expect(partial).toBeTruthy();
    expect(partial).not.toBe(sectionNotice('measurement', 'NO_DATA', false));
    expect(sectionNotice('measurement', 'AVAILABLE', true)).toBeNull();
    expect(sectionNotice('workout', 'AVAILABLE', true)).toBeNull();
  });

  it('uses a neutral notice for an unrecognised or missing availability', () => {
    for (const availability of ['PARTIAL_SOMETHING', undefined, null]) {
      const notice = sectionNotice('workout', availability, true);
      expect(notice).toMatch(/Chưa xác định/);
      expect(notice).not.toBe(sectionNotice('workout', 'NO_DATA', true));
      expect(notice).not.toBe(sectionNotice('workout', 'RESTRICTED', true));
    }
  });
});

describe('daysSinceLastWorkoutText', () => {
  it('treats missing as unknown but keeps zero as a real value', () => {
    expect(daysSinceLastWorkoutText(null)).toBeNull();
    expect(daysSinceLastWorkoutText(undefined)).toBeNull();
    expect(daysSinceLastWorkoutText(0)).toBeTruthy();
    expect(daysSinceLastWorkoutText(0)).not.toMatch(/^0 /);
    expect(daysSinceLastWorkoutText(12)).toContain('12');
  });
});

describe('continuity', () => {
  it('labels known states without raw codes and keeps them distinct', () => {
    const labels = CONTINUITY_STATES.map((state) => continuityStateLabel(state));
    expect(new Set(labels).size).toBe(CONTINUITY_STATES.length);
    labels.forEach((label, i) => expect(label).not.toContain(CONTINUITY_STATES[i]));
  });

  it('does not describe continuity as a training frequency', () => {
    for (const state of CONTINUITY_STATES) {
      expect(continuityStateLabel(state)).not.toMatch(/tần suất/i);
      expect(continuityExplanation({ state })).not.toMatch(/tần suất/i);
    }
  });

  it('uses a neutral fallback for an unknown state, never echoing the code', () => {
    expect(continuityStateLabel('SOMETHING_NEW')).not.toContain('SOMETHING_NEW');
    expect(continuityStateLabel(undefined)).toBe(continuityStateLabel('SOMETHING_NEW'));
    expect(continuityExplanation({ state: 'SOMETHING_NEW' })).not.toContain('SOMETHING_NEW');
    for (const known of CONTINUITY_STATES) {
      expect(continuityExplanation({ state: 'SOMETHING_NEW' })).not.toBe(continuityExplanation({ state: known }));
    }
  });

  it('does not classify regularity for recent activity', () => {
    const text = continuityExplanation({ state: 'RECENT_ACTIVITY_OBSERVED' });
    expect(text).toMatch(/chưa đánh giá mức độ đều đặn/);
    expect(text.replace('chưa đánh giá mức độ đều đặn', '')).not.toMatch(/đều đặn|thường xuyên|liên tục/);
  });

  it('does not attribute a restricted history to one specific cause', () => {
    const text = continuityExplanation({ state: 'RESTRICTED_HISTORY', safetyFlags: [] });
    expect(text).not.toMatch(/huấn luyện viên|quan hệ|kết thúc|100 ngày|thu hồi/i);
  });

  it('does not rule out unrecorded training for UNKNOWN_HISTORY', () => {
    expect(continuityExplanation({ state: 'UNKNOWN_HISTORY' })).toMatch(/chưa thể kết luận/);
    expect(continuityExplanation({ state: 'UNKNOWN_HISTORY' })).not.toMatch(/người mới bắt đầu\.$/);
  });

  it('mentions the return-to-training flag only when Backend set it', () => {
    const withFlag = continuityExplanation({ state: 'PROLONGED_INACTIVITY', safetyFlags: ['RETURN_AFTER_100_DAYS_V1'] });
    const withoutFlag = continuityExplanation({ state: 'PROLONGED_INACTIVITY', safetyFlags: [] });
    expect(withFlag).not.toBe(withoutFlag);
    expect(withFlag.startsWith(withoutFlag)).toBe(true);
    expect(continuityExplanation({ state: 'RECENT_ACTIVITY_OBSERVED', safetyFlags: ['RETURN_AFTER_100_DAYS_V1'] }))
      .toBe(continuityExplanation({ state: 'RECENT_ACTIVITY_OBSERVED' }));
  });
});

describe('metricLabel', () => {
  it('prefers the Vietnamese system name over the English V56 seed name', () => {
    expect(metricLabel('WEIGHT', 'Weight')).not.toBe('Weight');
    expect(metricLabel('BODY_FAT_PERCENTAGE', 'Body Fat Percentage')).not.toMatch(/Body Fat/);
  });

  it('keeps custom metric names exactly as provided', () => {
    expect(metricLabel('CUSTOM_GRIP_STRENGTH', 'Lực bóp tay (máy Camry)')).toBe('Lực bóp tay (máy Camry)');
    expect(metricLabel('CUSTOM_X', 'Vertical jump')).toBe('Vertical jump');
  });

  it('never uses the technical code as the primary label', () => {
    expect(metricLabel('CUSTOM_X', '   ')).toBe(UNNAMED_METRIC_LABEL);
    expect(metricLabel('CUSTOM_X')).toBe(UNNAMED_METRIC_LABEL);
    expect(metricLabel(undefined, null)).toBe(UNNAMED_METRIC_LABEL);
  });

  it('is not fooled by object prototype keys', () => {
    expect(metricLabel('toString')).toBe(UNNAMED_METRIC_LABEL);
    expect(metricLabel('constructor', 'Tên riêng')).toBe('Tên riêng');
    expect(continuityStateLabel('__proto__')).toBe(continuityStateLabel(undefined));
  });
});

describe('unitLabel and photoViewLabel', () => {
  it('shows symbols for known units and leaves unknown units untouched', () => {
    expect(unitLabel('PERCENT')).toBe('%');
    expect(unitLabel('kg')).toBe('kg');
    expect(unitLabel('BPM_CUSTOM')).toBe('BPM_CUSTOM');
    expect(unitLabel(null)).toBe('');
  });

  it('replaces the English V56 symbols for score/time/serving units', () => {
    for (const [code, englishSymbol] of [['SCORE', 'score'], ['SECOND', 's'], ['MINUTE', 'min'], ['SERVING', 'serving']]) {
      expect(unitLabel(code, { symbol: englishSymbol })).not.toBe(englishSymbol);
      expect(unitTypeLabel(code, { symbol: englishSymbol })).not.toBe(englishSymbol);
    }
    expect(withUnit(2, 'SERVING')).not.toMatch(/\bg\b|gram/i); // a serving implies no weight
  });

  it('shows BMI as a bare value: no "điểm" and no unit name', () => {
    expect(withUnit(24.5, 'SCORE', { metricCode: 'BMI', symbol: 'score' })).toBe('24.5');
    expect(unitLabel('SCORE', { metricCode: 'BMI' })).toBe('');
    expect(unitTypeLabel('SCORE', { metricCode: 'BMI', symbol: 'score' })).toBe('');
    expect(valueFieldLabel('Giá trị', unitTypeLabel('SCORE', { metricCode: 'BMI' }))).toBe('Giá trị:');
  });

  it('keeps "điểm" as the fallback for other SCORE metrics', () => {
    expect(withUnit(9, 'SCORE', { metricCode: 'VISCERAL_FAT', symbol: 'score' })).toBe('9 điểm');
    expect(withUnit(7, 'SCORE', { metricCode: 'CUSTOM_SLEEP_SCORE' })).toBe('7 điểm');
    expect(withUnit(7, 'SCORE')).toBe('7 điểm');
    expect(unitTypeLabel('SCORE', { metricCode: 'VISCERAL_FAT' })).toBe('Điểm');
    // the BMI exception is tied to the SCORE unit, not to the metric alone
    expect(withUnit(24.5, 'KG', { metricCode: 'BMI' })).toBe('24.5 kg');
  });

  it('keeps COUNT neutral for the known heart-rate metric: no per-minute or other wording is inferred', () => {
    for (const value of [62, 20, 250]) {
      expect(withUnit(value, 'COUNT', { metricCode: 'RESTING_HEART_RATE', symbol: 'count' })).toBe(`${value}`);
    }
    expect(unitTypeLabel('COUNT', { metricCode: 'RESTING_HEART_RATE', symbol: 'count' })).toBe('Số lượng');
  });

  it('keeps COUNT neutral for an unknown metric: no wrong word, no "lần", no raw "count"', () => {
    expect(withUnit(5, 'COUNT', { metricCode: 'CUSTOM_STEPS', symbol: 'count' })).toBe('5');
    expect(withUnit(5, 'COUNT')).toBe('5');
    expect(withUnit(5, 'count', { metricCode: 'CUSTOM_X' })).toBe('5');
    expect(unitTypeLabel('COUNT', { metricCode: 'CUSTOM_STEPS' })).toBe('Số lượng');
    expect(valueFieldLabel('Giá trị', unitTypeLabel('COUNT'))).toBe('Giá trị (Số lượng):');
  });

  it('keeps zero and custom units, and never leaves a dangling space', () => {
    expect(withUnit(0, 'KG')).toBe('0 kg');
    expect(withUnit(3, 'REP_CUSTOM', { symbol: 'vòng' })).toBe('3 vòng');
    expect(withUnit(3, null)).toBe('3');
    expect(unitTypeLabel('constructor')).toBe('constructor');
  });

  it('does not mix English into photo view labels and has a neutral fallback', () => {
    for (const type of ['FRONT', 'BACK', 'SIDE_LEFT', 'SIDE_RIGHT', 'OTHER']) {
      expect(photoViewLabel(type)).not.toMatch(/Front|Back|Side|Other/);
    }
    expect(photoViewLabel('TOP_DOWN')).not.toContain('TOP_DOWN');
  });
});

describe('progressSummaryResultTypeLabel', () => {
  it('does not attribute BLOCKED to policy and does not treat unknown types as BLOCKED', () => {
    const blocked = progressSummaryResultTypeLabel('BLOCKED');
    expect(blocked.label).not.toMatch(/CHÍNH SÁCH/);
    for (const unknown of ['POLICY_BLOCKED', 'NEW_TYPE', undefined, null]) {
      const label = progressSummaryResultTypeLabel(unknown);
      expect(label.tone).toBe('neutral');
      expect(label.label).not.toBe(blocked.label);
      if (unknown) expect(label.label).not.toContain(unknown);
    }
  });
});

describe('AI run labels', () => {
  it('keeps run status separate from result type and hides raw codes', () => {
    expect(aiRunStatusLabel('REJECTED_BY_VALIDATOR')).not.toContain('REJECTED');
    expect(aiRunStatusLabel('SUCCEEDED')).not.toBe(aiRunStatusLabel('FAILED'));
    expect(aiRunStatusLabel('PAUSED_NEW')).not.toContain('PAUSED_NEW');
    expect(aiRequestTypeLabel('F01_KNOWLEDGE_QUERY')).not.toContain('F01');
    expect(aiRequestTypeLabel('F01_GENERAL_KNOWLEDGE')).not.toBe(aiRequestTypeLabel('F01_KNOWLEDGE_QUERY'));
  });
});
