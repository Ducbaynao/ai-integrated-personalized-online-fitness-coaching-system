import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient.ts'
import { aiRunApi } from './aiRunApi.ts'

vi.mock('./apiClient.ts', () => ({ apiRequest: vi.fn() }))

describe('aiRunApi transport', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists runs with query parameters', async () => {
    await aiRunApi.listRuns({ page: 1, size: 20, status: 'SUCCEEDED', requestType: 'F01_GENERAL_KNOWLEDGE' })
    expect(apiRequest).toHaveBeenCalledWith(
      '/admin/ai/runs?page=1&size=20&status=SUCCEEDED&requestType=F01_GENERAL_KNOWLEDGE'
    )
  })

  it('lists runs with default empty params', async () => {
    await aiRunApi.listRuns()
    expect(apiRequest).toHaveBeenCalledWith('/admin/ai/runs')
  })

  it('gets run detail by id', async () => {
    await aiRunApi.getRunDetail('run-123')
    expect(apiRequest).toHaveBeenCalledWith('/admin/ai/runs/run-123')
  })

  it('gets F01 feature status', async () => {
    await aiRunApi.getF01FeatureStatus()
    expect(apiRequest).toHaveBeenCalledWith('/admin/ai/features/f01')
  })

  it('toggles F01 feature with POST body', async () => {
    await aiRunApi.toggleF01Feature({ enabled: false, reason: 'Maintenance' })
    expect(apiRequest).toHaveBeenCalledWith('/admin/ai/features/f01/toggle', {
      method: 'POST',
      body: JSON.stringify({ enabled: false, reason: 'Maintenance' }),
    })
  })
})
