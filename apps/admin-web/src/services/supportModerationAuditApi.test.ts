import { describe, expect, it, vi } from 'vitest'
import {
  auditApi,
  dataCorrectionApi,
  moderationApi,
  privilegedAccessApi,
  supportApi,
} from './supportModerationAuditApi.ts'
import * as apiClient from './apiClient.ts'

describe('supportModerationAuditApi', () => {
  it('calls supportApi.getSupportCases with query params', async () => {
    const mockResponse = { content: [], number: 0, size: 15, totalElements: 0, totalPages: 0 }
    const spy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockResponse)

    const res = await supportApi.getSupportCases({ status: 'OPEN', priority: 'HIGH', page: 0, size: 15 })

    expect(spy).toHaveBeenCalledWith('/admin/support-cases?status=OPEN&priority=HIGH&page=0&size=15')
    expect(res).toEqual(mockResponse)
    spy.mockRestore()
  })

  it('calls supportApi.createSupportCase with POST payload', async () => {
    const mockCase = { id: 'case-1' }
    const spy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockCase)

    const payload = { requesterId: 'req-1', subject: 'Lỗi số đo', description: 'Chi tiết', priority: 'HIGH' }
    const res = await supportApi.createSupportCase(payload)

    expect(spy).toHaveBeenCalledWith('/admin/support-cases', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
    expect(res).toEqual(mockCase)
    spy.mockRestore()
  })

  it('calls moderationApi.performAction with POST payload', async () => {
    const mockDetail = { summary: { id: 'mod-1' }, actions: [] }
    const spy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const payload = { actionType: 'WARNING', reason: 'Vi phạm ngôn từ', stepUpPassword: 'secret' }
    const res = await moderationApi.performAction('mod-1', payload)

    expect(spy).toHaveBeenCalledWith('/admin/moderation-cases/mod-1/actions', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
    expect(res).toEqual(mockDetail)
    spy.mockRestore()
  })

  it('calls privilegedAccessApi.requestAccess and revokeAccess', async () => {
    const mockAccess = { id: 'priv-1', currentlyActive: true }
    const spy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValue(mockAccess)

    const reqPayload = { targetUserId: 'u-1', purpose: 'DATA_CORRECTION', accessScope: '{}', reason: 'Sửa lỗi' }
    await privilegedAccessApi.requestAccess(reqPayload)
    expect(spy).toHaveBeenCalledWith('/admin/privileged-access', {
      method: 'POST',
      body: JSON.stringify(reqPayload),
    })

    await privilegedAccessApi.revokeAccess('priv-1', { reason: 'Hoàn tất' })
    expect(spy).toHaveBeenCalledWith('/admin/privileged-access/priv-1/revoke', {
      method: 'POST',
      body: JSON.stringify({ reason: 'Hoàn tất' }),
    })

    spy.mockRestore()
  })

  it('calls dataCorrectionApi.executeCorrection with step-up and proposed data', async () => {
    const mockCorr = { id: 'corr-1' }
    const spy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockCorr)

    const corrPayload = {
      supportCaseId: 'case-1',
      targetType: 'MEASUREMENT',
      targetId: 'meas-1',
      reason: 'Sửa cân nặng từ 75 về 72kg',
      proposedData: { value: 72.0, unit: 'KG' },
      privilegedAccessRequestId: 'priv-1',
      stepUpPassword: 'password123',
    }
    const res = await dataCorrectionApi.executeCorrection(corrPayload)

    expect(spy).toHaveBeenCalledWith('/admin/data-corrections', {
      method: 'POST',
      body: JSON.stringify(corrPayload),
    })
    expect(res).toEqual(mockCorr)
    spy.mockRestore()
  })

  it('calls auditApi.getAuditLogs with query filters', async () => {
    const mockLogs = { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0 }
    const spy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockLogs)

    const res = await auditApi.getAuditLogs({ action: 'ADMIN_DATA_CORRECTION', actorRole: 'ADMIN', page: 0, size: 20 })

    expect(spy).toHaveBeenCalledWith('/admin/audit-logs?actorRole=ADMIN&action=ADMIN_DATA_CORRECTION&page=0&size=20')
    expect(res).toEqual(mockLogs)
    spy.mockRestore()
  })
})
