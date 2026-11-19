import { request } from '@/services/apiClient';
import { ApiError } from '@/types/auth';
import {
  AcceptRescheduleApiRequest,
  AcceptRescheduleApiResponse,
  RejectRescheduleApiRequest,
  RejectRescheduleApiResponse,
  WorkoutRescheduleApiRequest,
  WorkoutRescheduleApiResponse,
  WorkoutRescheduleDetailApiResponse,
  WorkoutRescheduleFailureKind,
} from '@/types/aiWorkoutReschedule';

/** Preserve the underlying ApiError (including errorCode/fieldErrors) for consumers. */
export function getWorkoutRescheduleFailureKind(error: unknown): WorkoutRescheduleFailureKind {
  if (error instanceof ApiError) {
    if (error.status === 400) return 'VALIDATION';
    if (error.status === 401) return 'AUTHENTICATION';
    if (error.status === 403) return 'AUTHORITY';
    if (error.status === 404) return 'NOT_FOUND';
    if (error.status === 409) return 'CONFLICT';
    if (error.status === 408 || error.status === 504 || error.errorResponse?.errorCode === 'TIMEOUT') return 'TIMEOUT';
    if (error.status === 0) return 'NETWORK';
  }
  if (error instanceof Error) {
    if (error.name === 'AbortError') return 'CANCELLED';
    if (error.name === 'TimeoutError' || /timeout|timed out/i.test(error.message)) return 'TIMEOUT';
  }
  return 'OTHER';
}

function requireCommandKey(commandKey: string): void {
  if (!commandKey?.trim()) {
    throw new Error('commandKey is required for this workout reschedule intent.');
  }
}

export const aiWorkoutRescheduleApi = {
  generate(body: WorkoutRescheduleApiRequest) {
    requireCommandKey(body.commandKey);
    return request<WorkoutRescheduleApiResponse>('/ai/workout-reschedules', {
      method: 'POST',
      body,
    });
  },

  getRecommendation(recommendationId: string) {
    return request<WorkoutRescheduleDetailApiResponse>(
      `/ai/workout-reschedules/${encodeURIComponent(recommendationId)}`,
      { method: 'GET' }
    );
  },

  accept(recommendationId: string, body: AcceptRescheduleApiRequest) {
    requireCommandKey(body.commandKey);
    return request<AcceptRescheduleApiResponse>(
      `/ai/workout-reschedules/${encodeURIComponent(recommendationId)}/accept`,
      { method: 'POST', body }
    );
  },

  reject(recommendationId: string, body?: RejectRescheduleApiRequest) {
    return request<RejectRescheduleApiResponse>(
      `/ai/workout-reschedules/${encodeURIComponent(recommendationId)}/reject`,
      { method: 'POST', ...(body === undefined ? {} : { body }) }
    );
  },
};
