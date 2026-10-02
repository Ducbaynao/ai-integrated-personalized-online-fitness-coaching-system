import type {
  AdminExerciseDetail,
  AdminExerciseDraftRequest,
  AdminExerciseFormMetadata,
  AdminExercisePage,
  ArchiveExerciseRequest,
  ExerciseSearchParams,
  ExerciseVersionRequest,
  UpdateAdminExerciseRequest,
} from '../types/exercise.ts'
import { apiRequest } from './apiClient.ts'

export const exerciseQueryKeys = {
  all: ['admin-exercises'] as const,
  lists: () => [...exerciseQueryKeys.all, 'list'] as const,
  list: (params: ExerciseSearchParams) =>
    [...exerciseQueryKeys.lists(), params] as const,
  detail: (exerciseId: string) =>
    [...exerciseQueryKeys.all, 'detail', exerciseId] as const,
  metadata: () => [...exerciseQueryKeys.all, 'metadata'] as const,
}

export function getAdminExercises(params: ExerciseSearchParams): Promise<AdminExercisePage> {
  const search = new URLSearchParams({
    page: String(params.page),
    size: String(params.size),
  })
  if (params.query) search.set('query', params.query)
  if (params.status) search.set('status', params.status)

  return apiRequest(`/admin/exercises?${search.toString()}`)
}

export function getAdminExerciseDetail(exerciseId: string): Promise<AdminExerciseDetail> {
  return apiRequest(`/admin/exercises/${encodeURIComponent(exerciseId)}`)
}

export function getAdminExerciseFormMetadata(): Promise<AdminExerciseFormMetadata> {
  return apiRequest('/admin/exercises/metadata')
}

export function createAdminExerciseDraft(
  exercise: AdminExerciseDraftRequest,
): Promise<AdminExerciseDetail> {
  return apiRequest('/admin/exercises', {
    method: 'POST',
    body: JSON.stringify(exercise),
  })
}

export function updateAdminExerciseDraft(
  exerciseId: string,
  request: UpdateAdminExerciseRequest,
): Promise<AdminExerciseDetail> {
  return apiRequest(`/admin/exercises/${encodeURIComponent(exerciseId)}`, {
    method: 'PUT',
    body: JSON.stringify(request),
  })
}

export function activateAdminExercise(
  exerciseId: string,
  request: ExerciseVersionRequest,
): Promise<AdminExerciseDetail> {
  return apiRequest(`/admin/exercises/${encodeURIComponent(exerciseId)}/activate`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}

export function archiveAdminExercise(
  exerciseId: string,
  request: ArchiveExerciseRequest,
): Promise<AdminExerciseDetail> {
  return apiRequest(`/admin/exercises/${encodeURIComponent(exerciseId)}/archive`, {
    method: 'POST',
    body: JSON.stringify(request),
  })
}
