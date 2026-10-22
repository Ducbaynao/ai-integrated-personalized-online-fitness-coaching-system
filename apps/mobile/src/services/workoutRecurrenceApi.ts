import { request } from '@/services/apiClient';
import type { RecurrenceCreate, RecurrenceDecision, RecurrencePage, RecurrencePeriod, RecurrenceSeries, RecurrenceStatus, RecurrenceVersionCommand } from '@/types/workoutRecurrence';

const query = (page: number, status?: RecurrenceStatus) => {
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (status) params.set('status', status);
  return params.toString();
};

export const workoutRecurrenceApi = {
  listMine(page = 0, status?: RecurrenceStatus) {
    return request<RecurrencePage<RecurrenceSeries>>(`/planned-workout-series?${query(page, status)}`, { method: 'GET' });
  },
  listTrainer(studentId: string, page = 0, status?: RecurrenceStatus) {
    return request<RecurrencePage<RecurrenceSeries>>(`/students/${encodeURIComponent(studentId)}/planned-workout-series?${query(page, status)}`, { method: 'GET' });
  },
  confirm(body: RecurrenceCreate) {
    return request<RecurrenceSeries>('/planned-workout-series', { method: 'POST', body });
  },
  propose(studentId: string, body: RecurrenceCreate) {
    return request<RecurrenceSeries>(`/students/${encodeURIComponent(studentId)}/planned-workout-series`, { method: 'POST', body });
  },
  detail(seriesId: string) {
    return request<RecurrenceSeries>(`/planned-workout-series/${encodeURIComponent(seriesId)}`, { method: 'GET' });
  },
  periods(seriesId: string, page = 0) {
    return request<RecurrencePage<RecurrencePeriod>>(`/planned-workout-series/${encodeURIComponent(seriesId)}/periods?${query(page)}`, { method: 'GET' });
  },
  decide(seriesId: string, body: RecurrenceDecision) {
    return request<RecurrenceSeries>(`/planned-workout-series/${encodeURIComponent(seriesId)}/decisions`, { method: 'POST', body });
  },
  stop(seriesId: string, body: RecurrenceVersionCommand) {
    return request<RecurrenceSeries>(`/planned-workout-series/${encodeURIComponent(seriesId)}/stop`, { method: 'POST', body });
  },
  withdraw(seriesId: string, body: RecurrenceVersionCommand) {
    return request<RecurrenceSeries>(`/planned-workout-series/${encodeURIComponent(seriesId)}/withdraw`, { method: 'POST', body });
  },
};
