import { apiRequest } from './apiClient.ts'

export type PortionInput = { label: string; gramsPerServing: number | null;
  gramsPerMl: number | null; factorSource: string | null; mlFactorSource: string | null }
export type RevisionInput = { name: string; energyKcalPer100g: number | null;
  proteinGPer100g: number | null; carbohydrateGPer100g: number | null;
  fatGPer100g: number | null; sourceRecordVersion: string; sourceUrl: string;
  portions: PortionInput[] }
export type CatalogFood = { id: string; fdcId: number; dataType: 'Foundation';
  sourceName: string; sourceRelease: string; sourceDate: string;
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'; aggregateVersion: number;
  revisionId: string; revisionNumber: number; name: string;
  energyKcalPer100g: number | null; proteinGPer100g: number | null;
  carbohydrateGPer100g: number | null; fatGPer100g: number | null;
  sourceRecordVersion: string; sourceUrl: string;
  portions: Array<PortionInput & { id: string }> }

export const nutritionCatalogApi = {
  list: () => apiRequest<CatalogFood[]>('/admin/nutrition/catalog/foods'),
  draft: (body: { fdcId: number; sourceRelease: string; sourceDate: string;
    revision: RevisionInput; commandKey: string }) => apiRequest<CatalogFood>(
      '/admin/nutrition/catalog/foods', { method: 'POST', body: JSON.stringify(body) }),
  revise: (id: string, body: { expectedVersion: number; revision: RevisionInput; commandKey: string }) =>
    apiRequest<CatalogFood>(`/admin/nutrition/catalog/foods/${id}/revisions`,
      { method: 'POST', body: JSON.stringify(body) }),
  transition: (id: string, body: { expectedVersion: number; action: 'PUBLISH' | 'RETIRE';
    reason: string; commandKey: string }) => apiRequest<CatalogFood>(
      `/admin/nutrition/catalog/foods/${id}/transitions`, { method: 'POST', body: JSON.stringify(body) }),
}
