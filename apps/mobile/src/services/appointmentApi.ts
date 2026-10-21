import { request } from '@/services/apiClient';

export type AppointmentInterval = {
  localStart: string; localEnd: string; timezone: string;
  startUtcOffset: string; endUtcOffset: string;
};
export type Appointment = {
  appointmentId: string; version: number; studentId: string; trainerId: string | null;
  coachingPeriodId: string | null; plannedWorkoutId: string | null;
  status: 'SCHEDULED' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'ABSENT' | 'RESCHEDULED';
  startAt: string; endAt: string; timezone: string;
  localStart: string | null; localEnd: string | null;
  startUtcOffset: string | null; endUtcOffset: string | null;
};
export type AppointmentRequest = {
  requestId: string; kind: 'CREATE' | 'RESCHEDULE'; studentId: string; trainerId: string;
  coachingPeriodId: string; requestedBy: string; status: 'PENDING' | 'ACCEPTED' | 'REJECTED';
  version: number; appointmentId: string | null; plannedWorkoutId: string | null;
  observedAppointmentVersion: number | null; interval: AppointmentInterval | null;
  createdAt: string; decidedAt: string | null;
};
export type AppointmentPage<T> = { items: T[]; page: number; size: number; totalItems: number };
export type AppointmentRequestResult = { request: AppointmentRequest; appointment: Appointment | null; replayed: boolean };
export type AppointmentResult = { appointment: Appointment; replayed: boolean };

export const appointmentApi = {
  requests(page = 0, status?: AppointmentRequest['status']) {
    const filter = status ? `&status=${status}` : '';
    return request<AppointmentPage<AppointmentRequest>>(`/appointment-requests/me?page=${page}&size=20${filter}`);
  },
  requestDetail(id: string) {
    return request<AppointmentRequest>(`/appointment-requests/${encodeURIComponent(id)}`);
  },
  appointments(from: string, until: string, page = 0) {
    return request<AppointmentPage<Appointment>>(`/appointments/me?from=${encodeURIComponent(from)}&until=${encodeURIComponent(until)}&page=${page}&size=20`);
  },
  detail(id: string) {
    return request<Appointment>(`/appointments/${encodeURIComponent(id)}`);
  },
  create(studentId: string, body: AppointmentInterval & { plannedWorkoutId?: string; commandKey: string }) {
    return request<AppointmentRequestResult>(`/students/${encodeURIComponent(studentId)}/appointment-requests`, { method: 'POST', body });
  },
  decide(id: string, body: { decision: 'ACCEPT' | 'REJECT'; expectedRequestVersion: number; commandKey: string }) {
    return request<AppointmentRequestResult>(`/appointment-requests/${encodeURIComponent(id)}/decisions`, { method: 'POST', body });
  },
  reschedule(id: string, body: AppointmentInterval & { expectedAppointmentVersion: number; reason?: string; commandKey: string }) {
    return request<AppointmentRequestResult>(`/appointments/${encodeURIComponent(id)}/reschedule-requests`, { method: 'POST', body });
  },
  decideReschedule(id: string, body: { decision: 'ACCEPT' | 'REJECT'; expectedRequestVersion: number; expectedAppointmentVersion: number; commandKey: string }) {
    return request<AppointmentRequestResult>(`/appointment-reschedule-requests/${encodeURIComponent(id)}/decisions`, { method: 'POST', body });
  },
  cancel(id: string, body: { expectedAppointmentVersion: number; reason: string; commandKey: string }) {
    return request<AppointmentResult>(`/appointments/${encodeURIComponent(id)}/cancel`, { method: 'POST', body });
  },
};
