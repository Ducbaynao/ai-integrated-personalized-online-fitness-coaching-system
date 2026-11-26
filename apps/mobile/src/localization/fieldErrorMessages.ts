import type { FieldError } from '@/types/auth';

export const GENERIC_FIELD_ERROR = 'Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.';

/**
 * Field-specific copy, only where Backend defines the rule
 * (F02ContextBuilder: days 1–7, minutes 15–180, active goal, coach history scope).
 */
const FIELD_CODE_MESSAGES: Record<string, string> = {
  'preferredDaysPerWeek:INVALID_VALUE': 'Số buổi mỗi tuần phải từ 1 đến 7.',
  'preferredSessionMinutes:INVALID_VALUE': 'Thời lượng mỗi buổi phải từ 15 đến 180 phút.',
  'fitnessGoal.status:INVALID_STATUS': 'Cần có mục tiêu đang hoạt động trước khi thực hiện thao tác này.',
  'trainingContinuity:RESTRICTED':
    'Bạn chưa được phép xem lịch sử tập luyện của học viên; cần học viên xác nhận lịch sử.',
};

/** Codes from domain validators and Spring Bean Validation (GlobalExceptionHandler uses the annotation name). */
const CODE_MESSAGES: Record<string, string> = {
  REQUIRED: 'Vui lòng nhập thông tin này.',
  NotNull: 'Vui lòng nhập thông tin này.',
  NotBlank: 'Vui lòng nhập thông tin này.',
  NotEmpty: 'Vui lòng nhập thông tin này.',
  INVALID_TYPE: 'Giá trị không đúng định dạng.',
  TypeMismatch: 'Giá trị không đúng định dạng.',
  INVALID_DATE: 'Ngày không hợp lệ.',
  PAST_DATE: 'Ngày không được ở trong quá khứ.',
  NOT_CONFIRMED: 'Vui lòng xác nhận thông tin này.',
  Min: 'Giá trị nằm ngoài phạm vi cho phép.',
  Max: 'Giá trị nằm ngoài phạm vi cho phép.',
  DecimalMin: 'Giá trị nằm ngoài phạm vi cho phép.',
  DecimalMax: 'Giá trị nằm ngoài phạm vi cho phép.',
  Positive: 'Giá trị phải lớn hơn 0.',
  PositiveOrZero: 'Giá trị không được nhỏ hơn 0.',
  Size: 'Độ dài hoặc số lượng chưa phù hợp.',
};

/**
 * Maps field errors by `field`/`code` only. The server `message` is never displayed, whatever
 * its language, because it is diagnostic text and may contain internal details.
 */
export function localizeFieldErrorsByCode(fieldErrors: FieldError[] | null | undefined): Record<string, string> {
  const map: Record<string, string> = {};
  for (const fe of fieldErrors ?? []) {
    if (!fe?.field) continue;
    map[fe.field] =
      lookupOwn(FIELD_CODE_MESSAGES, `${fe.field}:${fe.code}`) ?? lookupOwn(CODE_MESSAGES, fe.code) ?? GENERIC_FIELD_ERROR;
  }
  return map;
}

function lookupOwn(table: Record<string, string>, key: string | null | undefined): string | undefined {
  return key && Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;
}
