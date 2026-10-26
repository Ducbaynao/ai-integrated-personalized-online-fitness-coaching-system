import { apiRequest } from './apiClient.ts'

export interface KnowledgeDocument {
  id: string
  documentCode: string
  title: string
  sourceId: string
  sourceName: string
  sourceUrl: string
  author: string
  documentType: string
  language: string
  createdAt: string
}

export type KnowledgeVersionStatus =
  | 'DRAFT'
  | 'ACTIVE'
  | 'ARCHIVED'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'PUBLISHED'

export type TechnicalReadiness =
  | 'DRAFT_STAGED'
  | 'TECHNICAL_VERIFICATION_PASSED'
  | 'HELD_AS_DRAFT'
  | 'READY'
  | 'NOT_READY'
  | 'HOLD'

export interface KnowledgeVersion {
  id: string
  knowledgeDocumentId: string
  versionNumber: number
  versionLabel: string
  status: KnowledgeVersionStatus
  technicalReadiness: TechnicalReadiness | null
  holdReason: string | null
  contentHash: string
  contentPath: string
  sourceBytes: number | null
  reviewEvidenceId: string | null
  lineageBaseDocumentId: string | null
  lineageBaseVersion: string | null
  content: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  publishedBy: string | null
  publishedAt: string | null
  activatedAt: string | null
  archivedAt: string | null
  createdAt: string
}

export interface ReviewKnowledgeVersionInput {
  decision: 'APPROVED' | 'REJECTED'
  notes?: string
}

export interface ArchiveKnowledgeVersionInput {
  reason?: string
}

export const knowledgeApi = {
  listDocuments: (page = 0, size = 50) =>
    apiRequest<KnowledgeDocument[]>(`/admin/content/knowledge/documents?page=${page}&size=${size}`),

  getDocument: (documentId: string) =>
    apiRequest<KnowledgeDocument>(`/admin/content/knowledge/documents/${documentId}`),

  listVersions: (documentId: string) =>
    apiRequest<KnowledgeVersion[]>(`/admin/content/knowledge/documents/${documentId}/versions`),

  getVersion: (versionId: string) =>
    apiRequest<KnowledgeVersion>(`/admin/content/knowledge/versions/${versionId}`),

  reviewVersion: (versionId: string, input: ReviewKnowledgeVersionInput) =>
    apiRequest<void>(`/admin/content/knowledge/versions/${versionId}/review`, {
      method: 'POST',
      body: JSON.stringify(input),
    }),

  publishVersion: (versionId: string) =>
    apiRequest<void>(`/admin/content/knowledge/versions/${versionId}/publish`, {
      method: 'POST',
    }),

  archiveVersion: (versionId: string, input?: ArchiveKnowledgeVersionInput) =>
    apiRequest<void>(`/admin/content/knowledge/versions/${versionId}/archive`, {
      method: 'POST',
      body: input ? JSON.stringify(input) : undefined,
    }),
}
