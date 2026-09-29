export type AccountStatus =
  | 'PENDING_VERIFICATION'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'DISABLED'
  | 'DELETION_PENDING'
  | 'DELETED'

export interface CurrentUser {
  id: string
  email: string
  displayName: string
  status: AccountStatus
  preferredLocale: string
  timezone: string
  roles: string[]
  permissions: string[]
  capabilities: {
    hasStudentProfile: boolean
    hasTrainerProfile: boolean
    canCoach: boolean
  }
}

export interface TokenPairResponse {
  tokenType: 'Bearer'
  accessToken: string
  accessTokenExpiresAt: string
  refreshToken: string
  refreshTokenExpiresAt: string
  user: CurrentUser
}

export interface StoredSession {
  accessToken: string
  refreshToken: string
}
