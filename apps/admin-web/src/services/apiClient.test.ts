import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest } from './apiClient.ts'
import { writeSession } from './sessionStorage.ts'
import { createTokenPair } from '../test/testData.ts'

describe('apiRequest', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses a single refresh request for concurrent 401 responses', async () => {
    writeSession(createTokenPair())
    let protectedRequests = 0
    let refreshRequests = 0

    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = input.toString()
      if (url.endsWith('/auth/token-refreshes')) {
        refreshRequests += 1
        return Response.json({
          ...createTokenPair(),
          accessToken: 'new-access-token-with-sufficient-length',
        })
      }

      protectedRequests += 1
      if (protectedRequests <= 2) {
        return Response.json(
          { errorCode: 'SESSION_EXPIRED', message: 'sensitive backend message' },
          { status: 401 },
        )
      }
      return Response.json({ ok: true })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(
      Promise.all([apiRequest('/protected/one'), apiRequest('/protected/two')]),
    ).resolves.toEqual([{ ok: true }, { ok: true }])
    expect(refreshRequests).toBe(1)
  })

  it('keeps stable field/code metadata but discards raw backend field messages', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({
      errorCode: 'VALIDATION_FAILED',
      message: 'raw form message',
      fieldErrors: [{ field: 'code', code: 'NotBlank', message: 'raw field message' }],
    }, { status: 400 })))

    const error = await apiRequest('/invalid').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldErrors).toEqual([{ field: 'code', code: 'NotBlank' }])
    expect(JSON.stringify(error)).not.toContain('raw field message')
    expect(JSON.stringify(error)).not.toContain('raw form message')
  })
})
