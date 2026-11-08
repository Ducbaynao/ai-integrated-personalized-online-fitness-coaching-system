import { describe, expect, it, vi } from 'vitest'
import { trainerApplicationApi } from './trainerApplicationApi.ts'
import * as apiClient from './apiClient.ts'

describe('trainerApplicationApi', () => {
  it('calls GET /admin/trainer-applications with pagination and status query params', async () => {
    const mockResponse = {
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 0,
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockResponse)

    const res = await trainerApplicationApi.getApplications({ status: 'PENDING', page: 1, size: 10 })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/trainer-applications?status=PENDING&page=1&size=10')
    expect(res).toEqual(mockResponse)
    apiRequestSpy.mockRestore()
  })

  it('calls GET /admin/trainer-applications/:id for details', async () => {
    const mockDetail = {
      id: 'app-1',
      trainerId: 'trainer-1',
      status: 'PENDING',
      submittedAt: '2026-11-08T10:00:00Z',
      reviewedAt: null,
      reviewedBy: null,
      rejectionReason: null,
      reviewNotes: null,
      applicantNote: 'Trainer notes',
      certificateIds: [],
      documentMediaIds: [],
      createdAt: '2026-11-08T10:00:00Z',
      updatedAt: '2026-11-08T10:00:00Z',
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDetail)

    const res = await trainerApplicationApi.getApplicationDetail('app-1')

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/trainer-applications/app-1')
    expect(res).toEqual(mockDetail)
    apiRequestSpy.mockRestore()
  })

  it('calls POST /admin/trainer-applications/:id/decisions with payload', async () => {
    const mockResult = {
      id: 'app-1',
      status: 'VERIFIED',
    }
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockResult)

    const res = await trainerApplicationApi.decideApplication('app-1', {
      decision: 'APPROVE',
      reviewNotes: 'All credentials checked and valid.',
    })

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/trainer-applications/app-1/decisions', {
      method: 'POST',
      body: JSON.stringify({
        decision: 'APPROVE',
        reviewNotes: 'All credentials checked and valid.',
      }),
    })
    expect(res).toEqual(mockResult)
    apiRequestSpy.mockRestore()
  })
})
