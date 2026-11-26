import { GENERIC_FIELD_ERROR, localizeFieldErrorsByCode } from '@/localization/fieldErrorMessages';
import { getWorkoutProposalFieldErrors } from '@/services/aiWorkoutProposalApi';
import { getWorkoutRescheduleFieldErrors } from '@/services/aiWorkoutRescheduleApi';
import { getWorkoutAdjustmentFieldErrors } from '@/services/aiWorkoutAdjustmentApi';
import { ApiError, FieldError } from '@/types/auth';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

const validationError = (fieldErrors: FieldError[]) =>
  new ApiError(400, 'Validation failed', {
    errorCode: 'VALIDATION_FAILED',
    message: 'Validation failed',
    timestamp: '',
    requestId: 'req-1',
    fieldErrors,
  });

describe('localizeFieldErrorsByCode', () => {
  it('never displays the server message, even when it is written in Vietnamese', () => {
    const map = localizeFieldErrorsByCode([
      { field: 'userReason', code: 'REQUIRED', message: 'Lý do bắt buộc (SQLSTATE 23502 tại cột user_reason)' },
      { field: 'note', code: 'SOMETHING_ELSE', message: 'Ghi chú vượt quá giới hạn bảng ai_runs' },
    ]);
    expect(Object.values(map).join(' ')).not.toMatch(/SQLSTATE|ai_runs|user_reason/);
    expect(map.note).toBe(GENERIC_FIELD_ERROR);
  });

  it('distinguishes required, format and range codes', () => {
    const map = localizeFieldErrorsByCode([
      { field: 'a', code: 'REQUIRED', message: 'x' },
      { field: 'b', code: 'INVALID_TYPE', message: 'x' },
      { field: 'c', code: 'Max', message: 'x' },
    ]);
    expect(new Set([map.a, map.b, map.c]).size).toBe(3);
    expect(map.a).toBe(localizeFieldErrorsByCode([{ field: 'z', code: 'NotNull', message: '' }]).z);
  });

  it('uses the Backend-defined range for F02 preference fields', () => {
    const map = localizeFieldErrorsByCode([
      { field: 'preferredDaysPerWeek', code: 'INVALID_VALUE', message: 'Preferred days per week must be between 1 and 7 (found: 9).' },
      { field: 'preferredSessionMinutes', code: 'INVALID_VALUE', message: 'x' },
      { field: 'otherField', code: 'INVALID_VALUE', message: 'x' },
    ]);
    expect(map.preferredDaysPerWeek).toMatch(/1 đến 7/);
    expect(map.preferredSessionMinutes).toMatch(/15 đến 180/);
    expect(map.otherField).toBe(GENERIC_FIELD_ERROR);
    expect(map.preferredDaysPerWeek).not.toMatch(/found/);
  });

  it('falls back safely for missing data and prototype-like codes', () => {
    expect(localizeFieldErrorsByCode(undefined)).toEqual({});
    expect(localizeFieldErrorsByCode(null)).toEqual({});
    const map = localizeFieldErrorsByCode([
      { field: '', code: 'REQUIRED', message: 'x' },
      { field: 'f', code: 'constructor', message: 'x' },
      { field: 'g', code: '', message: '' },
    ]);
    expect(Object.keys(map).sort()).toEqual(['f', 'g']);
    expect(map.f).toBe(GENERIC_FIELD_ERROR);
    expect(map.g).toBe(GENERIC_FIELD_ERROR);
  });
});

describe('AI workout API field errors', () => {
  it.each([
    ['proposal', getWorkoutProposalFieldErrors],
    ['adjustment', getWorkoutAdjustmentFieldErrors],
    ['reschedule', getWorkoutRescheduleFieldErrors],
  ])('%s maps by code instead of echoing server text', (_name, getFieldErrors) => {
    const map = getFieldErrors(
      validationError([{ field: 'occurrenceId', code: 'NotNull', message: 'Trường occurrenceId không được null' }])
    );
    expect(map.occurrenceId).toBeTruthy();
    expect(map.occurrenceId).not.toContain('occurrenceId');
    expect(getFieldErrors(new Error('plain'))).toEqual({});
  });
});
