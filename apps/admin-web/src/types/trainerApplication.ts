export type TrainerVerificationStatus =
  | 'NOT_SUBMITTED'
  | 'PENDING'
  | 'VERIFIED'
  | 'REJECTED'
  | 'SUSPENDED'

export interface AdminTrainerApplicationResponse {
  id: string
  trainerId: string
  status: TrainerVerificationStatus
  submittedAt: string | null
  reviewedAt: string | null
  reviewedBy: string | null
  rejectionReason: string | null
  reviewNotes: string | null
  applicantNote: string | null
  certificateIds: string[]
  documentMediaIds: string[]
  createdAt: string
  updatedAt: string
}

export interface AdminTrainerApplicationPageResponse {
  items: AdminTrainerApplicationResponse[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface DecideTrainerApplicationRequest {
  decision: 'APPROVE' | 'REJECT'
  rejectionReason?: string
  reviewNotes?: string
}

export interface AdminTrainerDocumentResponse {
  id: string
  originalFilename: string
  contentType: string
  sizeBytes: number
  scanStatus: string
  downloadUrl: string
  createdAt: string
}

export interface AdminTrainerCertificateResponse {
  id: string
  name: string
  issuer: string | null
  credentialNumber: string | null
  issuedOn: string | null
  expiresOn: string | null
  verificationStatus: string
  documentMediaId: string | null
  createdAt: string
}
