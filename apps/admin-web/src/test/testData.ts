import type { CurrentUser, TokenPairResponse } from '../types/auth.ts'

export function createCurrentUser(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: '2d107a28-f3f7-4cf5-a2a9-80d2db99ca64',
    email: 'admin@example.com',
    displayName: 'Quản trị viên',
    status: 'ACTIVE',
    preferredLocale: 'vi-VN',
    timezone: 'Asia/Ho_Chi_Minh',
    roles: ['ADMIN'],
    permissions: ['CATALOG_MANAGE'],
    capabilities: {
      hasStudentProfile: false,
      hasTrainerProfile: false,
      canCoach: false,
    },
    ...overrides,
  }
}

export function createTokenPair(user = createCurrentUser()): TokenPairResponse {
  return {
    tokenType: 'Bearer',
    accessToken: 'access-token-with-sufficient-length',
    accessTokenExpiresAt: '2026-09-29T10:00:00Z',
    refreshToken: 'refresh-token-with-sufficient-length',
    refreshTokenExpiresAt: '2026-10-29T10:00:00Z',
    user,
  }
}
