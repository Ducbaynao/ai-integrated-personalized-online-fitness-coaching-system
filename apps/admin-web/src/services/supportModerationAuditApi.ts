import { apiRequest } from './apiClient.ts'
import type {
  AddSupportCaseNoteRequest,
  AssignSupportCaseRequest,
  AuditLogEntry,
  AuditLogPageResponse,
  CreateModerationCaseRequest,
  CreateSupportCaseRequest,
  DataCorrection,
  DataCorrectionPageResponse,
  ExecuteDataCorrectionRequest,
  ModerationAction,
  ModerationCaseDetail,
  ModerationCasePageResponse,
  PerformModerationActionRequest,
  PrivilegedAccess,
  PrivilegedAccessPageResponse,
  RequestPrivilegedAccessDto,
  RevokePrivilegedAccessDto,
  SupportCaseDetail,
  SupportCasePageResponse,
  UpdateModerationStatusRequest,
  UpdateSupportCaseStatusRequest,
} from '../types/supportModerationAudit.ts'

export const supportApi = {
  getSupportCases: (params?: {
    status?: string
    priority?: string
    category?: string
    search?: string
    page?: number
    size?: number
  }) => {
    const searchParams = new URLSearchParams()
    if (params?.status) searchParams.set('status', params.status)
    if (params?.priority) searchParams.set('priority', params.priority)
    if (params?.category) searchParams.set('category', params.category)
    if (params?.search) searchParams.set('search', params.search)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    return apiRequest<SupportCasePageResponse>(`/admin/support-cases${query ? `?${query}` : ''}`)
  },

  getSupportCaseDetail: (caseId: string) =>
    apiRequest<SupportCaseDetail>(`/admin/support-cases/${caseId}`),

  createSupportCase: (payload: CreateSupportCaseRequest) =>
    apiRequest<SupportCaseDetail>(`/admin/support-cases`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  updateStatus: (caseId: string, payload: UpdateSupportCaseStatusRequest) =>
    apiRequest<SupportCaseDetail>(`/admin/support-cases/${caseId}/status`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  assignCase: (caseId: string, payload: AssignSupportCaseRequest) =>
    apiRequest<SupportCaseDetail>(`/admin/support-cases/${caseId}/assign`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  addNote: (caseId: string, payload: AddSupportCaseNoteRequest) =>
    apiRequest<SupportCaseDetail>(`/admin/support-cases/${caseId}/notes`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}

export const moderationApi = {
  getModerationCases: (params?: {
    status?: string
    priority?: string
    targetType?: string
    page?: number
    size?: number
  }) => {
    const searchParams = new URLSearchParams()
    if (params?.status) searchParams.set('status', params.status)
    if (params?.priority) searchParams.set('priority', params.priority)
    if (params?.targetType) searchParams.set('targetType', params.targetType)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    return apiRequest<ModerationCasePageResponse>(`/admin/moderation-cases${query ? `?${query}` : ''}`)
  },

  getModerationCaseDetail: (caseId: string) =>
    apiRequest<ModerationCaseDetail>(`/admin/moderation-cases/${caseId}`),

  createModerationCase: (payload: CreateModerationCaseRequest) =>
    apiRequest<ModerationCaseDetail>(`/admin/moderation-cases`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  updateStatus: (caseId: string, payload: UpdateModerationStatusRequest) =>
    apiRequest<ModerationCaseDetail>(`/admin/moderation-cases/${caseId}/status`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  performAction: (caseId: string, payload: PerformModerationActionRequest) =>
    apiRequest<ModerationAction>(`/admin/moderation-cases/${caseId}/actions`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}

export const privilegedAccessApi = {
  getRequests: (params?: {
    status?: string
    targetUserId?: string
    page?: number
    size?: number
  }) => {
    const searchParams = new URLSearchParams()
    if (params?.status) searchParams.set('status', params.status)
    if (params?.targetUserId) searchParams.set('targetUserId', params.targetUserId)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    return apiRequest<PrivilegedAccessPageResponse>(`/admin/privileged-access${query ? `?${query}` : ''}`)
  },

  getActive: async (targetUserId?: string): Promise<PrivilegedAccess | null> => {
    const path = targetUserId
      ? `/admin/privileged-access/active?targetUserId=${encodeURIComponent(targetUserId)}`
      : '/admin/privileged-access/active'
    const res = await apiRequest<PrivilegedAccess | undefined>(path)
    return res ?? null
  },

  getRequestDetail: (requestId: string) =>
    apiRequest<PrivilegedAccess>(`/admin/privileged-access/${requestId}`),

  requestAccess: (payload: RequestPrivilegedAccessDto) =>
    apiRequest<PrivilegedAccess>(`/admin/privileged-access`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  revokeAccess: (requestId: string, payload: RevokePrivilegedAccessDto) =>
    apiRequest<PrivilegedAccess>(`/admin/privileged-access/${requestId}/revoke`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}

export const dataCorrectionApi = {
  getCorrections: (params?: {
    supportCaseId?: string
    targetType?: string
    page?: number
    size?: number
  }) => {
    const searchParams = new URLSearchParams()
    if (params?.supportCaseId) searchParams.set('supportCaseId', params.supportCaseId)
    if (params?.targetType) searchParams.set('targetType', params.targetType)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    return apiRequest<DataCorrectionPageResponse>(`/admin/data-corrections${query ? `?${query}` : ''}`)
  },

  getCorrectionDetail: (correctionId: string) =>
    apiRequest<DataCorrection>(`/admin/data-corrections/${correctionId}`),

  executeCorrection: (payload: ExecuteDataCorrectionRequest) =>
    apiRequest<DataCorrection>(`/admin/data-corrections`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}

export const auditApi = {
  getAuditLogs: (params?: {
    actorUserId?: string
    actorRole?: string
    action?: string
    targetType?: string
    targetId?: string
    from?: string
    to?: string
    page?: number
    size?: number
  }) => {
    const searchParams = new URLSearchParams()
    if (params?.actorUserId) searchParams.set('actorUserId', params.actorUserId)
    if (params?.actorRole) searchParams.set('actorRole', params.actorRole)
    if (params?.action) searchParams.set('action', params.action)
    if (params?.targetType) searchParams.set('targetType', params.targetType)
    if (params?.targetId) searchParams.set('targetId', params.targetId)
    if (params?.from) searchParams.set('from', params.from)
    if (params?.to) searchParams.set('to', params.to)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    return apiRequest<AuditLogPageResponse>(`/admin/audit-logs${query ? `?${query}` : ''}`)
  },

  getAuditLogDetail: (id: string) =>
    apiRequest<AuditLogEntry>(`/admin/audit-logs/${id}`),
}
