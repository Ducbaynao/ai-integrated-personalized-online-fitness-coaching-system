import { request } from '@/services/apiClient';

export type AttentionPage<T> = { items: T[]; page: number; size: number; hasMore: boolean };
export type ReviewTimezone = { timezone: string; confirmedAt: string; version: number };
export type ReviewTask = {
  id: string; studentId: string; trainerId: string; coachingPeriodId: string;
  cycleStartDate: string; dueDate: string; timezone: string;
  status: 'OPEN' | 'COMPLETED' | 'CANCELLED'; version: number;
  summary: string | null; evidenceRefs: string[]; missingData: string[];
  completedAt: string | null; studentAcknowledgedAt: string | null;
};
export type AttentionSignal = {
  id: string; studentId: string; trainerId: string | null; coachingPeriodId: string | null;
  rule: 'MISSED_PLANNED_V1' | 'REVIEW_OVERDUE_V1' | 'RETURN_AFTER_100_DAYS_V1'; severity: 'LOW' | 'MEDIUM';
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'DISMISSED'; version: number;
  occurrenceId: string | null; occurrenceVersion: number | null;
  plannedStartAt: string | null; plannedEndAt: string | null;
  reviewTaskId: string | null; returnEvidence: ReturnAfter100DaysEvidence | null; detectedAt: string;
};
export type ReturnAfter100DaysEvidence = {
  previousExecutionId: string; previousExecutionVersion: number;
  currentExecutionId: string; currentExecutionVersion: number;
  previousCompletedAt: string; currentCompletedAt: string;
  previousLocalDate: string; currentLocalDate: string;
  timezone: string; calendarDays: number;
};
export type ReturnAfter100DaysStatus = {
  status: 'UNKNOWN' | 'NO_QUALIFYING_GAP' | 'DETECTED';
  reason: 'TIMEZONE_UNCONFIRMED' | 'PREVIOUS_COMPLETION_MISSING' | 'COMPLETION_TIME_MISSING' | null;
  timezone: string | null;
};
export type AttentionSignalEvent = {
  eventType: string; previousSeverity: string | null; resultingSeverity: string;
  previousStatus: string | null; resultingStatus: string;
  resultingVersion: number; reason: string | null; occurredAt: string;
};
export type CompleteReviewBody = {
  expectedVersion: number; commandKey: string; summary: string;
  evidenceRefs: string[]; missingData: string[];
};
export type SignalActionBody = {
  action: 'ACK' | 'RESOLVE' | 'DISMISS'; expectedVersion: number;
  commandKey: string; reason?: string;
};

export const attentionReviewApi = {
  async timezone(): Promise<ReviewTimezone | null> {
    return (await request<ReviewTimezone | undefined>('/review-timezone/me')) ?? null;
  },
  confirmTimezone(timezone: string, commandKey: string) {
    return request<ReviewTimezone>('/review-timezone/me', { method: 'PUT', body: { timezone, commandKey } });
  },
  reviews(page = 0, studentId?: string) {
    const prefix = studentId ? `/students/${encodeURIComponent(studentId)}/review-tasks` : '/review-tasks/me';
    return request<AttentionPage<ReviewTask>>(`${prefix}?page=${page}&size=20`);
  },
  review(id: string) { return request<ReviewTask>(`/review-tasks/${encodeURIComponent(id)}`); },
  complete(id: string, body: CompleteReviewBody) {
    return request<ReviewTask>(`/review-tasks/${encodeURIComponent(id)}/complete`, { method: 'POST', body });
  },
  acknowledgeReview(id: string, expectedVersion: number, commandKey: string) {
    return request<ReviewTask>(`/review-tasks/${encodeURIComponent(id)}/acknowledge`, {
      method: 'POST', body: { expectedVersion, commandKey },
    });
  },
  signals(page = 0, studentId?: string) {
    const prefix = studentId ? `/students/${encodeURIComponent(studentId)}/attention-signals` : '/attention-signals/me';
    return request<AttentionPage<AttentionSignal>>(`${prefix}?page=${page}&size=20`);
  },
  returnStatus(studentId?: string) {
    const path = studentId ? `/students/${encodeURIComponent(studentId)}/attention-return-status`
      : '/attention-return-status/me';
    return request<ReturnAfter100DaysStatus>(path);
  },
  signal(id: string) { return request<AttentionSignal>(`/attention-signals/${encodeURIComponent(id)}`); },
  signalHistory(id: string) {
    return request<AttentionSignalEvent[]>(`/attention-signals/${encodeURIComponent(id)}/history`);
  },
  act(id: string, body: SignalActionBody) {
    return request<AttentionSignal>(`/attention-signals/${encodeURIComponent(id)}/actions`, { method: 'POST', body });
  },
};
