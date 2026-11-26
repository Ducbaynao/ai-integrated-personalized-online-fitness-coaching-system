import type { PhotoViewType } from '@/types/measurement';

/**
 * Vietnamese presentation labels for Progress Engine / measurement enums.
 *
 * Rules (P7-016):
 * - Map only structured codes whose meaning is defined by Backend; never translate free text.
 * - An unrecognised code gets a neutral Vietnamese fallback, never the raw code and never a
 *   label that implies a specific outcome (e.g. "Ổn định" or "bị chặn bởi chính sách").
 * - Missing, zero, partial, unknown and restricted stay distinguishable.
 */

/** Names for the system metric catalog seeded by Flyway V56 (seed `display_name` is English). */
function lookupOwnEntry<T>(table: Record<string, T>, key: string | null | undefined): T | undefined {
  return key && Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;
}

const lookupOwn = (table: Record<string, string>, key: string | null | undefined) => lookupOwnEntry(table, key);

const SYSTEM_METRIC_LABELS: Record<string, string> = {
  WEIGHT: 'Cân nặng',
  HEIGHT: 'Chiều cao',
  BMI: 'Chỉ số BMI',
  BODY_FAT_PERCENTAGE: 'Tỷ lệ mỡ cơ thể',
  MUSCLE_MASS: 'Khối lượng cơ',
  BODY_WATER_PERCENTAGE: 'Tỷ lệ nước cơ thể',
  PROTEIN_PERCENTAGE: 'Tỷ lệ protein',
  VISCERAL_FAT: 'Mỡ nội tạng',
  BONE_MASS: 'Khối lượng xương',
  WAIST_CIRCUMFERENCE: 'Vòng eo',
  CHEST_CIRCUMFERENCE: 'Vòng ngực',
  HIP_CIRCUMFERENCE: 'Vòng mông',
  ARM_CIRCUMFERENCE: 'Vòng tay',
  THIGH_CIRCUMFERENCE: 'Vòng đùi',
  STRENGTH_LOAD: 'Mức tạ sức mạnh',
  RESTING_HEART_RATE: 'Nhịp tim khi nghỉ',
};

export const UNNAMED_METRIC_LABEL = 'Chỉ số chưa có tên hiển thị';

/**
 * System metrics use the Vietnamese catalog name; any other metric keeps the server/user-provided
 * name as-is. The technical code is never used as the primary label.
 */
export function metricLabel(code?: string | null, serverName?: string | null): string {
  const known = lookupOwn(SYSTEM_METRIC_LABELS, code);
  if (known) return known;
  const name = serverName?.trim();
  return name ? name : UNNAMED_METRIC_LABEL;
}

/**
 * Units seeded by Flyway V56. The seed symbols for SCORE/COUNT/SECOND/MINUTE/SERVING are English
 * (`score`, `count`, `s`, `min`, `serving`), so those are labelled here (P7-016 user decision).
 * `suffix` is appended to a number; `typeLabel` names the unit on its own.
 */
const UNIT_LABELS: Record<string, { suffix: string; typeLabel: string }> = {
  KG: { suffix: 'kg', typeLabel: 'kg' },
  G: { suffix: 'g', typeLabel: 'g' },
  LB: { suffix: 'lb', typeLabel: 'lb' },
  CM: { suffix: 'cm', typeLabel: 'cm' },
  M: { suffix: 'm', typeLabel: 'm' },
  KM: { suffix: 'km', typeLabel: 'km' },
  PERCENT: { suffix: '%', typeLabel: '%' },
  KCAL: { suffix: 'kcal', typeLabel: 'kcal' },
  ML: { suffix: 'ml', typeLabel: 'ml' },
  SCORE: { suffix: 'điểm', typeLabel: 'Điểm' },
  SECOND: { suffix: 'giây', typeLabel: 'Giây' },
  MINUTE: { suffix: 'phút', typeLabel: 'Phút' },
  // A serving has no implied weight; never convert it to grams here.
  SERVING: { suffix: 'phần', typeLabel: 'Phần' },
};

/**
 * COUNT has no meaning of its own. No metric is mapped to a COUNT wording: the only seeded COUNT
 * metric (RESTING_HEART_RATE) has dimension COUNT with no time base in V56, and no source/contract
 * defines it as per-minute. So COUNT appends nothing after the number and is named "Số lượng".
 * Never infer from the free-text display name or from the numeric range.
 */
const COUNT_NEUTRAL = { suffix: '', typeLabel: 'Số lượng' };

/**
 * Per-metric exceptions to a unit label, keyed `METRIC:UNIT` (P7-016 user decision).
 * BMI is stored with unit SCORE but is shown as a bare value ("BMI: 24,5"), so it has no unit text.
 */
const METRIC_UNIT_OVERRIDES: Record<string, { suffix: string; typeLabel: string }> = {
  'BMI:SCORE': { suffix: '', typeLabel: '' },
};

export interface UnitContext {
  /** Metric the value belongs to; used only for the explicit overrides above. */
  metricCode?: string | null;
  /** Symbol from the server, used only for units not known here (e.g. a custom unit). */
  symbol?: string | null;
}

function resolveUnit(code: string | null | undefined, context: UnitContext): { suffix: string; typeLabel: string } | null {
  const upper = code?.trim().toUpperCase();
  const override = context.metricCode ? lookupOwnEntry(METRIC_UNIT_OVERRIDES, `${context.metricCode}:${upper}`) : undefined;
  if (override) return override;
  if (upper === 'COUNT') return COUNT_NEUTRAL;
  const known = lookupOwnEntry(UNIT_LABELS, upper);
  if (known) return known;
  const custom = context.symbol?.trim() || code?.trim();
  return custom ? { suffix: custom, typeLabel: custom } : null;
}

/** Text appended to a number. COUNT and BMI add nothing rather than a wrong word. */
export function unitLabel(code?: string | null, context: UnitContext = {}): string {
  return resolveUnit(code, context)?.suffix ?? '';
}

/**
 * Standalone unit name, e.g. in a badge or "Giá trị (…)". COUNT → "Số lượng". Returns '' when the
 * value has no unit to name (BMI); callers then omit the unit text instead of showing "()".
 */
export function unitTypeLabel(code?: string | null, context: UnitContext = {}): string {
  return resolveUnit(code, context)?.typeLabel ?? '';
}

/** Input label such as "Giá trị (kg):", or "Giá trị:" when there is no unit to name. */
export function valueFieldLabel(base: string, unitTypeText: string): string {
  return unitTypeText ? `${base} (${unitTypeText}):` : `${base}:`;
}

/** `value unit` without a dangling space when the unit adds no text. */
export function withUnit(value: string | number, code?: string | null, context: UnitContext = {}): string {
  const suffix = unitLabel(code, context);
  return suffix ? `${value} ${suffix}` : `${value}`;
}

export type TrendTone = 'up' | 'down' | 'flat' | 'unknown';

/**
 * Backend TrendDirection: STABLE means the delta is exactly zero; INSUFFICIENT_DATA means the
 * delta could not be computed. A missing/unrecognised direction is unknown, not "stable".
 */
export function trendLabel(direction?: string | null): { label: string; symbol: string; tone: TrendTone } {
  switch (direction) {
    case 'INCREASED':
      return { label: 'Tăng', symbol: '▲', tone: 'up' };
    case 'DECREASED':
      return { label: 'Giảm', symbol: '▼', tone: 'down' };
    case 'STABLE':
      return { label: 'Không đổi', symbol: '→', tone: 'flat' };
    case 'INSUFFICIENT_DATA':
      return { label: 'Chưa đủ dữ liệu', symbol: '—', tone: 'unknown' };
    default:
      return { label: 'Chưa xác định xu hướng', symbol: '—', tone: 'unknown' };
  }
}

export const CONTINUITY_SECTION_TITLE = 'Tình trạng lịch sử tập luyện';

/**
 * Single source for continuity-state labels; aiProgressSummaryApi.ts re-exports it as
 * CONTINUITY_STATE_LABELS. Prefer continuityStateLabel() for display (it has a neutral fallback).
 */
export const CONTINUITY_LABELS: Readonly<Record<string, string>> = {
  CONFIRMED_NEW: 'Học viên mới bắt đầu',
  PROLONGED_INACTIVITY: 'Gián đoạn tập luyện dài hạn',
  RECENT_ACTIVITY_OBSERVED: 'Có hoạt động tập luyện gần đây',
  UNKNOWN_HISTORY: 'Lịch sử chưa xác định đầy đủ',
  RESTRICTED_HISTORY: 'Lịch sử bị giới hạn quyền truy cập',
};

export function continuityStateLabel(state?: string | null): string {
  return lookupOwn(CONTINUITY_LABELS, state) ?? 'Chưa xác định trạng thái';
}

export const RETURN_AFTER_100_DAYS_FLAG = 'RETURN_AFTER_100_DAYS_V1';

/**
 * Vietnamese explanation built only from structured continuity fields. It restates what Backend
 * determined; it does not translate Backend's English rationale, guess a cause, classify
 * regularity or give a safety conclusion.
 */
export function continuityExplanation(continuity: {
  state?: string | null;
  safetyFlags?: string[] | null;
}): string {
  switch (continuity.state) {
    case 'CONFIRMED_NEW':
      return 'Hồ sơ ban đầu xác nhận người mới bắt đầu và hệ thống chưa ghi nhận buổi tập hoàn thành nào.';
    case 'PROLONGED_INACTIVITY': {
      const base = 'Buổi tập hoàn thành gần nhất được ghi nhận cách thời điểm tổng hợp từ 100 ngày trở lên.';
      return continuity.safetyFlags?.includes(RETURN_AFTER_100_DAYS_FLAG)
        ? `${base} Hệ thống gắn lưu ý trở lại tập sau thời gian dài gián đoạn.`
        : base;
    }
    case 'RECENT_ACTIVITY_OBSERVED':
      return 'Có buổi tập hoàn thành gần đây được ghi nhận. Hệ thống chưa đánh giá mức độ đều đặn.';
    case 'UNKNOWN_HISTORY':
      return 'Hệ thống chưa ghi nhận buổi tập hoàn thành nào và chưa có xác nhận là người mới bắt đầu, nên chưa thể kết luận về lịch sử tập luyện trước đây.';
    case 'RESTRICTED_HISTORY':
      return 'Phạm vi dữ liệu bạn được phép xem chưa đủ để đánh giá lịch sử tập luyện.';
    default:
      return 'Chưa có diễn giải cho trạng thái này.';
  }
}

/** `null`/`undefined` means unknown (no text); 0 is a real value and is shown. */
export function daysSinceLastWorkoutText(days?: number | null): string | null {
  if (days === null || days === undefined) return null;
  if (days === 0) return 'Có buổi tập trong ngày tổng hợp';
  return `${days} ngày kể từ buổi tập gần nhất`;
}

const PHOTO_VIEW_LABELS: Record<PhotoViewType, string> = {
  FRONT: 'Chính diện',
  BACK: 'Phía sau',
  SIDE_LEFT: 'Nghiêng trái',
  SIDE_RIGHT: 'Nghiêng phải',
  OTHER: 'Góc khác',
};

export const PHOTO_VIEW_OPTIONS: { type: PhotoViewType; label: string }[] = (
  ['FRONT', 'BACK', 'SIDE_LEFT', 'SIDE_RIGHT', 'OTHER'] as PhotoViewType[]
).map((type) => ({ type, label: PHOTO_VIEW_LABELS[type] }));

export function photoViewLabel(viewType?: string | null): string {
  return lookupOwn(PHOTO_VIEW_LABELS, viewType) ?? 'Chưa rõ góc chụp';
}

export type SectionAvailability = 'AVAILABLE' | 'NO_DATA' | 'RESTRICTED' | string | null | undefined;

/**
 * Empty-state copy for a dashboard section. RESTRICTED (permission scope) is never shown as
 * "no data". Returns null when the section has data to render.
 */
export function sectionNotice(
  section: 'measurement' | 'workout',
  availability: SectionAvailability,
  hasRows: boolean
): string | null {
  if (availability === 'RESTRICTED') {
    return section === 'measurement'
      ? 'Dữ liệu số đo trong giai đoạn này bị giới hạn theo phạm vi quyền xem hiện tại.'
      : 'Dữ liệu tập luyện trong giai đoạn này bị giới hạn theo phạm vi quyền xem hiện tại.';
  }
  if (availability === 'NO_DATA') {
    return section === 'measurement'
      ? 'Chưa có dữ liệu số đo trong giai đoạn này.'
      : 'Chưa có lịch tập hoặc buổi tập nào hoàn thành trong giai đoạn này.';
  }
  if (availability !== 'AVAILABLE') {
    return 'Chưa xác định được tình trạng dữ liệu của phần này.';
  }
  if (!hasRows) {
    return section === 'measurement'
      ? 'Chưa có chỉ số nào đủ dữ liệu để so sánh trong giai đoạn này.'
      : null;
  }
  return null;
}

export type ResultTone = 'info' | 'warning' | 'danger' | 'neutral';

/**
 * F04 resultType. BLOCKED is produced both by validator rejection and by processing failures,
 * so the label does not attribute it to policy. Unrecognised types stay neutral.
 */
export function progressSummaryResultTypeLabel(resultType?: string | null): { label: string; tone: ResultTone } {
  switch (resultType) {
    case 'INFORMATION':
      return { label: 'THÔNG TIN TIẾN ĐỘ', tone: 'info' };
    case 'INSUFFICIENT_DATA':
      return { label: 'CHƯA ĐỦ DỮ LIỆU ĐỂ KẾT LUẬN', tone: 'warning' };
    case 'BLOCKED':
      return { label: 'KHÔNG THỂ HIỂN THỊ KẾT LUẬN', tone: 'danger' };
    default:
      return { label: 'CHƯA XÁC ĐỊNH LOẠI KẾT QUẢ', tone: 'neutral' };
  }
}
