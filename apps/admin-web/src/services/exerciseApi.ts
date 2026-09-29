import type {
  AdminExerciseDetail,
  AdminExercisePage,
  ExerciseSearchParams,
} from '../types/exercise.ts'
import { apiRequest } from './apiClient.ts'

export const exerciseQueryKeys = {
  all: ['admin-exercises'] as const,
  list: (params: ExerciseSearchParams) =>
    [...exerciseQueryKeys.all, 'list', params] as const,
  detail: (exerciseId: string) =>
    [...exerciseQueryKeys.all, 'detail', exerciseId] as const,
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
