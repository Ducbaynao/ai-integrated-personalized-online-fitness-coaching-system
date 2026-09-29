import type { CurrentUser, TokenPairResponse } from '../types/auth.ts'
import { apiRequest } from './apiClient.ts'

export interface LoginInput {
  email: string
  password: string
}

export function login(input: LoginInput): Promise<TokenPairResponse> {
  return apiRequest('/auth/sessions', {
    method: 'POST',
    authenticated: false,
    body: JSON.stringify({ ...input, deviceName: 'Admin Web' }),
  })
}

export function getCurrentUser(): Promise<CurrentUser> {
  return apiRequest('/users/me')
}

export function logout(refreshToken: string): Promise<void> {
  return apiRequest('/auth/sessions', {
    method: 'DELETE',
    body: JSON.stringify({ refreshToken }),
  })
}
