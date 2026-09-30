import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createExerciseDetail, createExerciseMetadata, createExercisePage } from '../test/exerciseTestData.ts'
import {
  createAdminExerciseDraft,
  exerciseQueryKeys,
  getAdminExerciseFormMetadata,
  getAdminExercises,
  updateAdminExerciseDraft,
} from './exerciseApi.ts'

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

  it('uses only the Admin metadata endpoint for draft form options', async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json(createExerciseMetadata()))
    vi.stubGlobal('fetch', fetchMock)

    await getAdminExerciseFormMetadata()

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/admin/exercises/metadata', expect.anything())
    expect(exerciseQueryKeys.metadata()).toEqual(['admin-exercises', 'metadata'])
  })

  it('sends create and versioned update payloads without extra fields', async () => {
    const draft = {
      code: 'BARBELL_SQUAT',
      name: 'Barbell Squat',
      categoryCode: null,
      description: null,
      instructions: null,
      difficulty: null,
      movementPattern: null,
      unilateral: false,
      tagCodes: [],
      variations: [],
    }
    const fetchMock = vi.fn().mockImplementation(async () => Response.json(
      createExerciseDetail({ status: 'DRAFT' }),
    ))
    vi.stubGlobal('fetch', fetchMock)

    await createAdminExerciseDraft(draft)
    await updateAdminExerciseDraft('2c5f9430-c360-4b32-b70a-d6f92b76bfd4', {
      expectedVersion: 3,
      exercise: draft,
    })

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/admin/exercises', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify(draft),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/admin/exercises/2c5f9430-c360-4b32-b70a-d6f92b76bfd4', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ expectedVersion: 3, exercise: draft }),
    }))
  })
})
