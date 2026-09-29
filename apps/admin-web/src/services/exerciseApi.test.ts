import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createExercisePage } from '../test/exerciseTestData.ts'
import { exerciseQueryKeys, getAdminExercises } from './exerciseApi.ts'

describe('exercise API contract', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('serializes only supported list parameters and uses stable query keys', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(createExercisePage()))
    vi.stubGlobal('fetch', fetchMock)
    const params = { query: 'Barbell Squat', status: 'ACTIVE' as const, page: 2, size: 50 }

    await getAdminExercises(params)

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/admin/exercises?page=2&size=50&query=Barbell+Squat&status=ACTIVE',
      expect.objectContaining({ headers: expect.any(Headers) }),
    )
    expect(exerciseQueryKeys.list(params)).toEqual([
      'admin-exercises',
      'list',
      params,
    ])
  })
})
