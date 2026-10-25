import { apiRequest } from './apiClient.ts'

export interface AdminAiRunSummary {
  id: string
  requestType: string
  status: string
  purpose: string
  correlationId: string
  queuedAt: string
  startedAt?: string | null
  completedAt?: string | null
  latencyMs?: number | null
  errorCode?: string | null
  errorMessage?: string | null
  inputTokenCount?: number | null
  outputTokenCount?: number | null
  totalTokenCount?: number | null
  evaluationOnly: boolean
  createdAt: string
}

export interface AdminAiRunPageResponse {
  items: AdminAiRunSummary[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface AdminAiValidationSummary {
  id: string
  validatorCode: string
  passed: boolean
  severity: string
  details?: string | null
  createdAt: string
}

export interface AdminAiRunChunkReference {
  chunkId: string
  rank: number
  similarityScore?: number | null
}

export interface AdminAiRunDetail extends AdminAiRunSummary {
  validations: AdminAiValidationSummary[]
  citations: AdminAiRunChunkReference[]
}

export interface AdminAiFeatureStatus {
  featureCode: string
  enabled: boolean
  description: string
  updatedBy?: string | null
  updatedAt?: string | null
}

export interface ToggleAiFeatureInput {
  enabled: boolean
  reason?: string
}

export const aiRunApi = {
  listRuns: (params?: { page?: number; size?: number; status?: string; requestType?: string }) => {
    const query = new URLSearchParams()
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))
    if (params?.status) query.set('status', params.status)
    if (params?.requestType) query.set('requestType', params.requestType)
    const queryString = query.toString()
    return apiRequest<AdminAiRunPageResponse>(`/admin/ai/runs${queryString ? `?${queryString}` : ''}`)
  },

  getRunDetail: (runId: string) =>
    apiRequest<AdminAiRunDetail>(`/admin/ai/runs/${runId}`),

  getF01FeatureStatus: () =>
    apiRequest<AdminAiFeatureStatus>('/admin/ai/features/f01'),

  toggleF01Feature: (input: ToggleAiFeatureInput) =>
    apiRequest<AdminAiFeatureStatus>('/admin/ai/features/f01/toggle', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
}
