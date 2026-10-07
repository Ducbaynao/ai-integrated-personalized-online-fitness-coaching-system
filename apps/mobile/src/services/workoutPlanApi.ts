import { request } from '@/services/apiClient';
import { createCommandKey } from '@/services/coachingApi';
import {
  PublishWorkoutVersionInput, WorkoutDraftInput, WorkoutPage, WorkoutPlanCommandResponse,
  WorkoutPlanDetail, WorkoutPlanStatus, WorkoutPlanSummary, WorkoutPlanVersionDetail,
  WorkoutPlanVersionSummary,
} from '@/types/workoutPlan';

export const WORKOUT_PLAN_PAGE_SIZE = 20;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function normalizeWorkoutPlanId(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toLowerCase();
  return UUID_PATTERN.test(normalized) ? normalized : null;
}

const pageQuery = (page: number, size: number) => `page=${page}&size=${size}`;

export const workoutPlanApi = {
  list(page = 0, size = WORKOUT_PLAN_PAGE_SIZE) {
    return request<WorkoutPage<WorkoutPlanSummary>>(`/workout-plans?${pageQuery(page, size)}`, { method: 'GET' });
  },
  current() { return request<WorkoutPlanDetail>('/workout-plans/current', { method: 'GET' }); },
  detail(planId: string) { return request<WorkoutPlanDetail>(`/workout-plans/${planId}`, { method: 'GET' }); },
  versions(planId: string, page = 0, size = WORKOUT_PLAN_PAGE_SIZE) {
    return request<WorkoutPage<WorkoutPlanVersionSummary>>(`/workout-plans/${planId}/versions?${pageQuery(page, size)}`, { method: 'GET' });
  },
  version(planId: string, versionId: string) {
    return request<WorkoutPlanVersionDetail>(`/workout-plans/${planId}/versions/${versionId}`, { method: 'GET' });
  },
  create(studentId: string, draft: WorkoutDraftInput, commandKey = createCommandKey()) {
    return request<WorkoutPlanCommandResponse>('/workout-plans', { method: 'POST', body: { studentId, ...draft, commandKey } });
  },
  updateDraft(planId: string, expectedVersion: number, draft: WorkoutDraftInput, commandKey = createCommandKey()) {
    return request<WorkoutPlanCommandResponse>(`/workout-plans/${planId}/draft`, { method: 'PUT', body: { expectedVersion, ...draft, commandKey } });
  },
  activate(planId: string, expectedVersion: number, commandKey = createCommandKey()) {
    return request<WorkoutPlanCommandResponse>(`/workout-plans/${planId}/activate`, { method: 'POST', body: { expectedVersion, commandKey } });
  },
  transition(planId: string, expectedVersion: number, target: WorkoutPlanStatus, reason: string | null, commandKey = createCommandKey()) {
    return request<WorkoutPlanCommandResponse>(`/workout-plans/${planId}/transitions`, { method: 'POST', body: { expectedVersion, target, reason, commandKey } });
  },
  publish(planId: string, expectedVersion: number, input: PublishWorkoutVersionInput, commandKey = createCommandKey()) {
    return request<WorkoutPlanCommandResponse>(`/workout-plans/${planId}/versions`, { method: 'POST', body: { expectedVersion, ...input, commandKey } });
  },
  successor(sourcePlanId: string, sourceVersionId: string, name: string, commandKey = createCommandKey()) {
    return request<WorkoutPlanCommandResponse>('/workout-plans/successors', { method: 'POST', body: { sourcePlanId, sourceVersionId, name: name.trim(), commandKey } });
  },
};
