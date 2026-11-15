import { describe, expect, it, vi } from 'vitest'
import { userAdminApi } from './userAdminApi.ts'
import * as apiClient from './apiClient.ts'

describe('userAdminApi', () => {
  it('calls GET /admin/users with query params', async () => {
    const mockResponse = {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockResponse)

    const res = await userAdminApi.getUsers({
      search: 'nguyen',
      status: 'ACTIVE',
      role: 'STUDENT',
      page: 1,
      size: 10,
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users?search=nguyen&status=ACTIVE&role=STUDENT&page=1&size=10')
    expect(res).toEqual(mockResponse)
    apiRequestSpy.mockRestore()
  })

  it('calls GET /admin/users/:userId for detail', async () => {
    const mockDetail = {
      id: 'user-1',
      email: 'test@example.com',
      displayName: 'Test User',
      phoneNumber: null,
      status: 'ACTIVE',
      preferredLocale: 'vi',
      timezone: 'Asia/Ho_Chi_Minh',
      emailVerifiedAt: '2026-11-01T00:00:00Z',
      createdAt: '2026-11-01T00:00:00Z',
      updatedAt: '2026-11-01T00:00:00Z',
      lastLoginAt: null,
      roles: ['STUDENT'],
      permissions: [],
      studentProfile: null,
      trainerProfile: null,
      statusHistory: [],
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const res = await userAdminApi.getUserDetail('user-1')

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users/user-1')
    expect(res).toEqual(mockDetail)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/users/:userId/suspend with reason and step-up', async () => {
    const mockDetail = { id: 'user-1', status: 'SUSPENDED' }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const res = await userAdminApi.suspendUser('user-1', {
      reason: 'Spamming',
      stepUpPassword: 'secretPassword123',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users/user-1/suspend', {
      method: 'POST',
      body: JSON.stringify({
        reason: 'Spamming',
        stepUpPassword: 'secretPassword123',
      }),
    })
    expect(res).toEqual(mockDetail)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/users/:userId/restore with reason', async () => {
    const mockDetail = { id: 'user-1', status: 'ACTIVE' }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const res = await userAdminApi.restoreUser('user-1', {
      reason: 'Appeal approved',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users/user-1/restore', {
      method: 'POST',
      body: JSON.stringify({
        reason: 'Appeal approved',
      }),
    })
    expect(res).toEqual(mockDetail)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/users/:userId/disable with reason and step-up', async () => {
    const mockDetail = { id: 'user-1', status: 'DISABLED' }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const res = await userAdminApi.disableUser('user-1', {
      reason: 'Fraudulent activity',
      stepUpPassword: 'secretPassword123',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users/user-1/disable', {
      method: 'POST',
      body: JSON.stringify({
        reason: 'Fraudulent activity',
        stepUpPassword: 'secretPassword123',
      }),
    })
    expect(res).toEqual(mockDetail)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/users/:userId/force-logout with reason', async () => {
    const mockResp = { revokedSessionCount: 2, message: 'Sessions revoked successfully' }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockResp)

    const res = await userAdminApi.forceLogoutUser('user-1', {
      reason: 'Compromised device',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users/user-1/force-logout', {
      method: 'POST',
      body: JSON.stringify({
        reason: 'Compromised device',
      }),
    })
    expect(res).toEqual(mockResp)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/users/:userId/roles with role payload', async () => {
    const mockDetail = { id: 'user-1', roles: ['STUDENT', 'TRAINER'] }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const res = await userAdminApi.changeUserRole('user-1', {
      roleCode: 'TRAINER',
      action: 'ASSIGN',
      reason: 'Promoted to coach',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/users/user-1/roles', {
      method: 'POST',
      body: JSON.stringify({
        roleCode: 'TRAINER',
        action: 'ASSIGN',
        reason: 'Promoted to coach',
      }),
    })
    expect(res).toEqual(mockDetail)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/auth/step-up', async () => {
    const mockStepUp = {
      stepUpAuthenticationId: 'step-up-id-123',
      expiresAt: '2026-11-09T13:00:00Z',
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockStepUp)

    const res = await userAdminApi.issueStepUp({
      actionCode: 'USER_SUSPEND',
      password: 'password123',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/auth/step-up', {
      method: 'POST',
      body: JSON.stringify({
        actionCode: 'USER_SUSPEND',
        password: 'password123',
      }),
    })
    expect(res).toEqual(mockStepUp)
    apiRequestSpy.mockRestore()
  })

  it('calls GET /admin/dashboard/summary', async () => {
    const mockSummary = {
      pendingTrainerApplications: 3,
      totalUsers: 100,
      activeUsers: 85,
      suspendedUsers: 5,
      disabledUsers: 2,
      pendingVerificationUsers: 8,
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockSummary)

    const res = await userAdminApi.getDashboardSummary()

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/dashboard/summary')
    expect(res).toEqual(mockSummary)
    apiRequestSpy.mockRestore()
  })
})
