import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createExerciseDetail, createExerciseMetadata, createExercisePage } from '../test/exerciseTestData.ts'
import {
  activateAdminExercise,
  archiveAdminExercise,
  createAdminExerciseDraft,
  exerciseQueryKeys,
  getAdminExerciseFormMetadata,
  getCanonicalReplacementPreview,
  getAdminExercises,
  updateAdminExerciseDraft,
  setAdminExerciseCanonicalReplacement,
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

  it('uses the contract lifecycle paths and exact versioned payloads', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => Response.json(createExerciseDetail()))
    vi.stubGlobal('fetch', fetchMock)
    const exerciseId = '2c5f9430-c360-4b32-b70a-d6f92b76bfd4'

    await activateAdminExercise(exerciseId, { expectedVersion: 7 })
    await archiveAdminExercise(exerciseId, { expectedVersion: 8, reason: 'Nội dung đã lỗi thời' })

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/v1/admin/exercises/${exerciseId}/activate`,
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ expectedVersion: 7 }) }),
    )
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/admin/exercises/${exerciseId}/archive`,
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ expectedVersion: 8, reason: 'Nội dung đã lỗi thời' }),
      }),
    )
  })

  it('uses the read-only preview and exact canonical replacement mutation contracts', async () => {
    const exerciseId = '2c5f9430-c360-4b32-b70a-d6f92b76bfd4'
    const targetExerciseId = 'cf2961f5-8392-4d55-b48a-257c48a99ca7'
    const preview = {
      sourceExercise: { id: exerciseId, code: 'OLD_SQUAT', name: 'Old Squat', status: 'ARCHIVED' },
      expectedVersion: 4,
      currentTarget: null,
      usageImpact: { availability: 'NOT_AVAILABLE', count: null },
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(preview))
      .mockResolvedValueOnce(Response.json(createExerciseDetail({ status: 'ARCHIVED' })))
    vi.stubGlobal('fetch', fetchMock)

    await getCanonicalReplacementPreview(exerciseId)
    await setAdminExerciseCanonicalReplacement(exerciseId, {
      expectedVersion: 4,
      targetExerciseId,
      reason: 'Nội dung trùng lặp',
    })

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      `/api/v1/admin/exercises/${exerciseId}/canonical-replacement/preview`,
      expect.objectContaining({ headers: expect.any(Headers) }),
    )
    expect(exerciseQueryKeys.canonicalReplacementPreview(exerciseId)).toEqual([
      'admin-exercises',
      'canonical-replacement-preview',
      exerciseId,
    ])
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      `/api/v1/admin/exercises/${exerciseId}/canonical-replacement`,
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({
          expectedVersion: 4,
          targetExerciseId,
          reason: 'Nội dung trùng lặp',
        }),
      }),
    )
  })
})
