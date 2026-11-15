export interface SupportCaseSummary {
  id: string
  caseNumber: string
  requesterId: string
  requesterName: string
  assignedToAdminId: string | null
  assignedToAdminName: string | null
  subject: string
  category: string | null
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  openedAt: string
  resolvedAt: string | null
  closedAt: string | null
  updatedAt: string
}

export interface SupportCaseEvent {
  id: string
  eventType: string
  actorUserId: string | null
  actorName: string | null
  notes: string
  payloadJson: string | null
  createdAt: string
}

export interface SupportCaseDetail {
  summary: SupportCaseSummary
  events: SupportCaseEvent[]
}

export interface SupportCasePageResponse {
  content: SupportCaseSummary[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface CreateSupportCaseRequest {
  requesterId: string
  subject: string
  description: string
  priority?: string
  category?: string
}

export interface UpdateSupportCaseStatusRequest {
  status: string
  note?: string
}

export interface AssignSupportCaseRequest {
  assignedToAdminId: string
}

export interface AddSupportCaseNoteRequest {
  note: string
}

export interface ModerationCaseSummary {
  id: string
  targetType: string
  targetId: string
  targetDisplayName: string | null
  assignedToAdminId: string | null
  assignedToAdminName: string | null
  status: 'OPEN' | 'INVESTIGATING' | 'ACTIONED' | 'DISMISSED'
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  createdReason: string | null
  notes: string | null
  openedAt: string
  resolvedAt: string | null
  updatedAt: string
}

export interface ModerationAction {
  id: string
  actionType: 'WARNING' | 'SUSPENSION' | 'DISABLE' | 'CONTENT_REMOVAL' | 'NO_ACTION'
  targetUserId: string | null
  targetUserName: string | null
  performedByAdminId: string | null
  performedByAdminName: string | null
  reason: string
  metadataJson: string | null
  performedAt: string
}

export interface ModerationCaseDetail {
  summary: ModerationCaseSummary
  actions: ModerationAction[]
}

export interface ModerationCasePageResponse {
  content: ModerationCaseSummary[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface CreateModerationCaseRequest {
  targetType: string
  targetId: string
  reason: string
  priority?: string
  notes?: string
}

export interface UpdateModerationStatusRequest {
  status: string
  notes?: string
}

export interface PerformModerationActionRequest {
  actionType: string
  targetUserId?: string
  reason: string
  metadata?: Record<string, unknown>
  stepUpPassword?: string
  stepUpAuthenticationId?: string
}

export interface PrivilegedAccess {
  id: string
  requestedByAdminId: string | null
  requestedByAdminName: string | null
  approvedByAdminId: string | null
  approvedByAdminName: string | null
  targetUserId: string
  targetUserName: string | null
  purpose: string
  accessScopeJson: string | null
  reason: string
  relatedSupportCaseId: string | null
  supportCaseNumber: string | null
  status: 'REQUESTED' | 'ACTIVE' | 'EXPIRED' | 'REVOKED' | 'REJECTED'
  requestedAt: string
  approvedAt: string | null
  openedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  currentlyActive: boolean
}

export interface PrivilegedAccessPageResponse {
  content: PrivilegedAccess[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface RequestPrivilegedAccessDto {
  targetUserId: string
  purpose: string
  accessScope: string
  reason: string
  relatedSupportCaseId?: string
  durationMinutes?: number
  stepUpPassword?: string
  stepUpAuthenticationId?: string
}

export interface RevokePrivilegedAccessDto {
  reason: string
}

export interface DataCorrection {
  id: string
  supportCaseId: string
  caseNumber: string | null
  targetType: 'MEASUREMENT' | 'USER_PROFILE' | string
  targetId: string
  beforeDataJson: string
  proposedDataJson: string
  appliedDataJson: string
  validationResultJson: string
  requestedBy: string
  requestedByName: string | null
  appliedBy: string | null
  appliedByName: string | null
  createdAt: string
  appliedAt: string | null
}

export interface DataCorrectionPageResponse {
  content: DataCorrection[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface ExecuteDataCorrectionRequest {
  supportCaseId: string
  targetType: 'MEASUREMENT' | 'USER_PROFILE' | string
  targetId: string
  reason: string
  proposedData: Record<string, unknown>
  privilegedAccessRequestId: string
  stepUpPassword?: string
  stepUpAuthenticationId?: string
}

export interface AuditLogEntry {
  id: string
  actorUserId: string | null
  actorRole: string
  actorName: string | null
  action: string
  targetType: string
  targetId: string
  beforeDataJson: string | null
  afterDataJson: string | null
  metadataJson: string | null
  ipAddress: string | null
  userAgent: string | null
  occurredAt: string
}

export interface AuditLogPageResponse {
  content: AuditLogEntry[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}
