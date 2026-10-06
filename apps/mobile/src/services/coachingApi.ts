import { request } from '@/services/apiClient';
import {
  CoachingOutcome,
  CoachingRelationship,
  CoachingStatusEvent,
  DataAccessLevel,
  DataScope,
  Page,
  PermissionSummary,
  RelationshipAction,
  ResumeAction,
  SharingDecision,
  SharingGrantInput,
  StudentLookup,
  TrainerDirectoryItem,
} from '@/types/coaching';

export const COACHING_PAGE_SIZE = 20;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function normalizeCoachingId(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const normalized = value.trim().toLowerCase();
  return UUID_PATTERN.test(normalized) ? normalized : null;
}

export function createCommandKey(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined;
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === 'x' ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

function pageQuery(page: number, size: number): string {
  return `page=${page}&size=${size}`;
}

export const coachingApi = {
  getTrainers(query = '', page = 0, size = COACHING_PAGE_SIZE) {
    const params = new URLSearchParams({ query: query.trim(), page: String(page), size: String(size) });
    return request<Page<TrainerDirectoryItem>>(`/coaching/trainers?${params}`, { method: 'GET' });
  },
  lookupStudent(email: string) {
    return request<StudentLookup>(`/coaching/students/lookup?email=${encodeURIComponent(email.trim())}`, { method: 'GET' });
  },
  getRelationships(page = 0, size = COACHING_PAGE_SIZE) {
    return request<Page<CoachingRelationship>>(`/coaching/relationships/me?${pageQuery(page, size)}`, { method: 'GET' });
  },
  getRelationship(relationshipId: string) {
    return request<CoachingOutcome>(`/coaching/relationships/${relationshipId}`, { method: 'GET' });
  },
  getRelationshipHistory(relationshipId: string, page = 0, size = COACHING_PAGE_SIZE) {
    return request<Page<CoachingStatusEvent>>(`/coaching/relationships/${relationshipId}/history?${pageQuery(page, size)}`, { method: 'GET' });
  },
  requestTrainer(trainerId: string, commandKey = createCommandKey()) {
    return request<CoachingOutcome>('/coaching/relationships/requests', { method: 'POST', body: { counterpartyId: trainerId, commandKey } });
  },
  inviteStudent(studentId: string, commandKey = createCommandKey()) {
    return request<CoachingOutcome>('/coaching/relationships/invitations', { method: 'POST', body: { counterpartyId: studentId, commandKey } });
  },
  relationshipAction(relationshipId: string, action: RelationshipAction, expectedVersion: number, reason?: string, commandKey = createCommandKey()) {
    return request<CoachingOutcome>(`/coaching/relationships/${relationshipId}/${action}`, { method: 'POST', body: { expectedVersion, commandKey, ...(reason?.trim() ? { reason: reason.trim() } : {}) } });
  },
  requestResume(relationshipId: string, expectedVersion: number, reason?: string, commandKey = createCommandKey()) {
    return request<CoachingOutcome>(`/coaching/relationships/${relationshipId}/resume-requests`, { method: 'POST', body: { expectedVersion, commandKey, ...(reason?.trim() ? { reason: reason.trim() } : {}) } });
  },
  resumeAction(relationshipId: string, resumeRequestId: string, action: ResumeAction, expectedRelationshipVersion: number, expectedRequestVersion: number, commandKey = createCommandKey()) {
    return request<CoachingOutcome>(`/coaching/relationships/${relationshipId}/resume-requests/${resumeRequestId}/${action}`, { method: 'POST', body: { expectedRelationshipVersion, expectedRequestVersion, commandKey } });
  },
  getSharingSummary(relationshipId: string) {
    return request<PermissionSummary>(`/coaching/relationships/${relationshipId}/sharing-permissions/summary`, { method: 'GET' });
  },
  setSharing(relationshipId: string, input: SharingGrantInput, commandKey = createCommandKey()) {
    return request(`/coaching/relationships/${relationshipId}/sharing-permissions`, { method: 'POST', body: { ...input, commandKey } });
  },
  revokeSharing(relationshipId: string, permissionId: string, expectedPermissionVersion: number, reason?: string, commandKey = createCommandKey()) {
    return request(`/coaching/relationships/${relationshipId}/sharing-permissions/${permissionId}/revoke`, { method: 'POST', body: { expectedPermissionVersion, commandKey, ...(reason?.trim() ? { reason: reason.trim() } : {}) } });
  },
};

export const accessLevels: DataAccessLevel[] = ['VIEW', 'CONTRIBUTE', 'MANAGE'];
export const sharingDecisions: SharingDecision[] = ['ALLOW', 'DENY'];
export const dataScopes: DataScope[] = ['FITNESS_GOAL', 'WORKOUT_PLAN', 'WORKOUT_HISTORY', 'WORKOUT_PLAN_HISTORY', 'BODY_METRICS', 'PROGRESS_PHOTOS', 'NUTRITION_LOGS', 'AI_RECOMMENDATIONS'];
