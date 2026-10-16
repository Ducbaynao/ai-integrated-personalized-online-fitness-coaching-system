import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient.ts'
import { nutritionCatalogApi, type RevisionInput } from './nutritionCatalogApi.ts'

vi.mock('./apiClient.ts', () => ({ apiRequest: vi.fn() }))

const revision: RevisionInput = {
  name: 'Foundation oats', energyKcalPer100g: 400, proteinGPer100g: 0,
  carbohydrateGPer100g: null, fatGPer100g: null, sourceRecordVersion: '2026-10',
  sourceUrl: 'https://fdc.nal.usda.gov/food-details/12345/nutrients',
  portions: [{ label: 'cup', gramsPerServing: 40, gramsPerMl: null,
    factorSource: 'USDA Foundation portion weight', mlFactorSource: null }],
}

describe('governed nutrition catalog transport', () => {
  beforeEach(() => vi.clearAllMocks())

  it('keeps release, source, revision and actor command tokens on Admin writes', async () => {
    const draft = { fdcId: 12345, sourceRelease: 'Foundation 2026-10',
      sourceDate: '2026-10-16', revision, commandKey: 'draft-key' }
    const revise = { expectedVersion: 1, revision: { ...revision, name: 'Updated oats' },
      commandKey: 'revision-key' }
    const transition = { expectedVersion: 2, action: 'PUBLISH' as const,
      reason: 'Reviewed', commandKey: 'publish-key' }
    await nutritionCatalogApi.draft(draft)
    await nutritionCatalogApi.revise('food-id', revise)
    await nutritionCatalogApi.transition('food-id', transition)
    expect(apiRequest).toHaveBeenNthCalledWith(1, '/admin/nutrition/catalog/foods',
      { method: 'POST', body: JSON.stringify(draft) })
    expect(apiRequest).toHaveBeenNthCalledWith(2,
      '/admin/nutrition/catalog/foods/food-id/revisions',
      { method: 'POST', body: JSON.stringify(revise) })
    expect(apiRequest).toHaveBeenNthCalledWith(3,
      '/admin/nutrition/catalog/foods/food-id/transitions',
      { method: 'POST', body: JSON.stringify(transition) })
  })
})
