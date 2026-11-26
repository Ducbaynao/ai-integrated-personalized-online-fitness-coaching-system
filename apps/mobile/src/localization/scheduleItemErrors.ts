/**
 * Per-item error copy for workout schedule batch confirmation and schedule proposal creation.
 *
 * Backend (WorkoutScheduleBatchErrorAdvice / ScheduleProposalErrorAdvice) always sends itemErrors with
 * `field: null` and a fixed English `message`; the meaning is carried only by `code`. The codes below are
 * the ones WorkoutScheduleBatchService raises together with a clientItemId (schedule proposals reuse the
 * same inspection). The server message is never displayed, whatever its language.
 */
export const SCHEDULE_ITEM_ERROR_FALLBACK = 'Dữ liệu buổi tập chưa hợp lệ. Vui lòng kiểm tra lại.';

const ITEM_CODE_MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: SCHEDULE_ITEM_ERROR_FALLBACK,
  WORKOUT_SCHEDULE_DUPLICATE: 'Buổi tập tại thời điểm này đã tồn tại.',
  WORKOUT_SCHEDULE_OVERLAP: 'Trùng thời gian với một buổi tập khác.',
  WORKOUT_SCHEDULE_APPOINTMENT_NOT_QUALIFYING: 'Cuộc hẹn liên kết không phù hợp với buổi tập này.',
  WORKOUT_SCHEDULE_SOURCE_MISMATCH: 'Buổi tập không còn khớp với kế hoạch hiện tại.',
  WORKOUT_SCHEDULE_COACH_REQUIRED_UNSUPPORTED: 'Buổi tập cần huấn luyện viên nhưng chưa có cuộc hẹn liên kết.',
};

export function scheduleItemErrorMessage(code: unknown): string {
  return typeof code === 'string' && Object.prototype.hasOwnProperty.call(ITEM_CODE_MESSAGES, code)
    ? ITEM_CODE_MESSAGES[code]
    : SCHEDULE_ITEM_ERROR_FALLBACK;
}
