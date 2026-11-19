import type { WorkoutRescheduleStructuredPayload } from '@/types/aiWorkoutReschedule';

export interface SupportedReschedulePayload {
  supported: true;
  summary: string;
  reasoning: string[];
  usedData: string[];
  missingData: string[];
  limitations: string[];
  previousPlannedStartAt: string;
  proposedStartAt: string;
  proposedEndAt: string;
  timezone: string;
  supervisionRequirement: 'SELF_PERFORMABLE' | 'COACH_OPTIONAL' | 'COACH_REQUIRED';
}

export type ReschedulePayloadView = SupportedReschedulePayload | { supported: false; reason: string };

const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);
const string = (value: unknown): value is string => typeof value === 'string' && value.trim().length > 0;
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(string);
const instant = (value: unknown): value is string =>
  string(value) && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value) && !Number.isNaN(Date.parse(value));
const localTime = (value: unknown): value is string =>
  string(value) && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value);
const utcOffset = (value: unknown): value is string =>
  string(value) && /^[+-](0\d|1[0-4]):[0-5]\d$/.test(value);
const timezone = (value: unknown): value is string => {
  if (!string(value)) return false;
  try { new Intl.DateTimeFormat('en-US', { timeZone: value }); return true; }
  catch { return false; }
};
const knownFields = (value: Record<string, unknown>, fields: readonly string[]) =>
  Object.keys(value).every((key) => fields.includes(key));

/** Check the open OpenAPI object before showing or deciding on AI-supplied fields. */
export function readReschedulePayload(raw: WorkoutRescheduleStructuredPayload | null | unknown,
  occurrenceId: string, sourceSupervision: string | null): ReschedulePayloadView {
  if (!object(raw) || raw.recommendationType !== 'WORKOUT_RESCHEDULE_PROPOSAL' ||
      !string(raw.summary) || !strings(raw.reasoning) || !strings(raw.usedData) ||
      !strings(raw.missingData) || (raw.limitations !== undefined && !strings(raw.limitations))) {
    return { supported: false, reason: 'Dữ liệu đề xuất chưa nhận diện được hoặc chưa đầy đủ.' };
  }
  if (!knownFields(raw, ['recommendationType', 'summary', 'reasoning', 'usedData', 'missingData', 'limitations', 'confidence', 'reschedule'])) {
    return { supported: false, reason: 'Đề xuất có trường mới chưa được ứng dụng hỗ trợ.' };
  }
  const change = raw.reschedule;
  if (!object(change) || change.occurrenceId !== occurrenceId || !instant(change.previousPlannedStartAt) ||
      !object(change.destination)) {
    return { supported: false, reason: 'Buổi tập hoặc mốc lịch trong đề xuất không khớp dữ liệu đang xem.' };
  }
  const destination = change.destination;
  if (!knownFields(change, ['occurrenceId', 'planSessionId', 'sessionName', 'previousPlannedStartAt', 'originalPlannedStartAt', 'supervisionRequirement', 'destination']) ||
      !knownFields(destination, ['localStart', 'localEnd', 'timezone', 'startUtcOffset', 'endUtcOffset', 'proposedStartAt', 'proposedEndAt'])) {
    return { supported: false, reason: 'Đề xuất có trường lịch mới chưa được ứng dụng hỗ trợ.' };
  }
  const supervision = change.supervisionRequirement;
  if (!['SELF_PERFORMABLE', 'COACH_OPTIONAL', 'COACH_REQUIRED'].includes(String(supervision)) ||
      !sourceSupervision || supervision !== sourceSupervision ||
      !instant(destination.proposedStartAt) || !instant(destination.proposedEndAt) ||
      !timezone(destination.timezone) || !localTime(destination.localStart) || !localTime(destination.localEnd) ||
      !utcOffset(destination.startUtcOffset) || !utcOffset(destination.endUtcOffset) ||
      Date.parse(destination.proposedStartAt) >= Date.parse(destination.proposedEndAt)) {
    return { supported: false, reason: 'Giờ, múi giờ hoặc yêu cầu giám sát chưa được xác minh; không thể quyết định.' };
  }
  return {
    supported: true,
    summary: raw.summary,
    reasoning: raw.reasoning,
    usedData: raw.usedData,
    missingData: raw.missingData,
    limitations: raw.limitations ?? [],
    previousPlannedStartAt: change.previousPlannedStartAt,
    proposedStartAt: destination.proposedStartAt,
    proposedEndAt: destination.proposedEndAt,
    timezone: destination.timezone,
    supervisionRequirement: supervision as SupportedReschedulePayload['supervisionRequirement'],
  };
}
