import { request } from '@/services/apiClient';
import type { CancelPlannedWorkoutRequest, PlannedWorkoutChangeResult, PlannedWorkoutOccurrence, PlannedWorkoutOccurrencePage, ReschedulePlannedWorkoutRequest } from '@/types/workoutSchedule';
import type { ConfirmWorkoutScheduleBatchRequest, ConfirmedWorkoutScheduleBatch } from '@/types/workoutScheduleBatch';

export const WORKOUT_SCHEDULE_PAGE_SIZE = 20;

export const workoutScheduleApi = {
  confirmBatch(body: ConfirmWorkoutScheduleBatchRequest) {
    return request<ConfirmedWorkoutScheduleBatch>('/planned-workout-batches', { method: 'POST', body });
  },
  list(from: string, until: string, page = 0, size = WORKOUT_SCHEDULE_PAGE_SIZE) {
    const params = new URLSearchParams({ from, until, page: String(page), size: String(size) });
    return request<PlannedWorkoutOccurrencePage>(`/planned-workouts?${params}`, { method: 'GET' });
  },
  listForTrainer(studentId: string, from: string, until: string, page = 0, size = WORKOUT_SCHEDULE_PAGE_SIZE) {
    const params = new URLSearchParams({ from, until, page: String(page), size: String(size) });
    return request<PlannedWorkoutOccurrencePage>(`/students/${encodeURIComponent(studentId)}/planned-workouts?${params}`, { method: 'GET' });
  },
  detail(occurrenceId: string) {
    return request<PlannedWorkoutOccurrence>(`/planned-workouts/${encodeURIComponent(occurrenceId)}`, { method: 'GET' });
  },
  reschedule(occurrenceId: string, body: ReschedulePlannedWorkoutRequest) {
    return request<PlannedWorkoutChangeResult>(`/planned-workouts/${encodeURIComponent(occurrenceId)}/reschedule`, { method: 'POST', body });
  },
  cancel(occurrenceId: string, body: CancelPlannedWorkoutRequest) {
    return request<PlannedWorkoutChangeResult>(`/planned-workouts/${encodeURIComponent(occurrenceId)}/cancel`, { method: 'POST', body });
  },
};
