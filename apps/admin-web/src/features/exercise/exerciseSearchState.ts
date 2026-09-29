import type { ExerciseLifecycleStatus, ExerciseSearchParams } from '../../types/exercise.ts'

const VALID_STATUSES = new Set<ExerciseLifecycleStatus>(['DRAFT', 'ACTIVE', 'ARCHIVED'])
const VALID_PAGE_SIZES = new Set([10, 20, 50, 100])

export function parseExerciseSearchParams(search: URLSearchParams): ExerciseSearchParams {
  const rawQuery = search.get('query')?.trim().slice(0, 120) || undefined
  const rawStatus = search.get('status') as ExerciseLifecycleStatus | null
  const rawPage = Number(search.get('page'))
  const rawSize = Number(search.get('size'))

  return {
    query: rawQuery,
    status: rawStatus && VALID_STATUSES.has(rawStatus) ? rawStatus : undefined,
    page: Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0,
    size: VALID_PAGE_SIZES.has(rawSize) ? rawSize : 20,
  }
}

export function serializeExerciseSearchParams(params: ExerciseSearchParams): URLSearchParams {
  const search = new URLSearchParams()
  if (params.query) search.set('query', params.query)
  if (params.status) search.set('status', params.status)
  if (params.page > 0) search.set('page', String(params.page))
  if (params.size !== 20) search.set('size', String(params.size))
  return search
}
