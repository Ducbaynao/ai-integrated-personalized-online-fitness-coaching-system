export type AccountStatus =
  | 'PENDING_VERIFICATION'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'DISABLED'
  | 'DELETION_PENDING'
  | 'DELETED'

export interface AdminUserSummaryResponse {
  id: string
  email: string
  displayName: string
  phoneNumber: string | null
  status: AccountStatus
  roles: string[]
  hasStudentProfile: boolean
  hasTrainerProfile: boolean
  trainerVerificationStatus: string | null
  createdAt: string
  lastLoginAt: string | null
}

export interface AdminUserPageResponse {
  content: AdminUserSummaryResponse[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface StudentProfileDto {
  dateOfBirth: string | null
  gender: string | null
  trainingExperienceLevel: string | null
  trainingExperienceMonths: number | null
  availableDaysPerWeek: number | null
  preferredSessionMinutes: number | null
  onboardingCompleted: boolean
}

export interface TrainerProfileDto {
  publicSlug: string | null
  bio: string | null
  yearsExperience: number | null
  verificationStatus: string
  activityStatus: string
  verifiedAt: string | null
  verifiedBy: string | null
  isAcceptingStudents: boolean
  isActive: boolean
}

export interface AccountStatusHistoryDto {
  id: string
  fromStatus: AccountStatus | null
  toStatus: AccountStatus
  changedBy: string | null
  changedByName: string | null
  reason: string
  changedAt: string
}

export interface AdminUserDetailResponse {
  id: string
  email: string
  displayName: string
  phoneNumber: string | null
  status: AccountStatus
  preferredLocale: string
  timezone: string
  emailVerifiedAt: string | null
  createdAt: string
  updatedAt: string
  lastLoginAt: string | null
  roles: string[]
  permissions: string[]
  studentProfile: StudentProfileDto | null
  trainerProfile: TrainerProfileDto | null
  statusHistory: AccountStatusHistoryDto[]
}

export interface AdminUserActionRequest {
  reason: string
  stepUpPassword?: string
  stepUpAuthenticationId?: string
}

export interface ChangeUserRoleRequest {
  roleCode: string
  action: 'ASSIGN' | 'REVOKE'
  reason: string
  stepUpPassword?: string
  stepUpAuthenticationId?: string
}

export interface ForceLogoutResponse {
  revokedSessionCount: number
  message: string
}

export interface StepUpRequest {
  actionCode: string
  password: string
}

export interface StepUpResponse {
  stepUpAuthenticationId: string
  expiresAt: string
}

export interface AdminDashboardSummaryResponse {
  pendingTrainerApplications: number
  totalUsers: number
  activeUsers: number
  suspendedUsers: number
  disabledUsers: number
  pendingVerificationUsers: number
}

export type AdminDashboardSummary = AdminDashboardSummaryResponse
