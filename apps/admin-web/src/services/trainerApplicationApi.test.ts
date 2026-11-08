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

  it('calls GET /admin/trainer-applications/:id/documents', async () => {
    const mockDocs = [
      {
        id: 'doc-1',
        originalFilename: 'cert.pdf',
        contentType: 'application/pdf',
        sizeBytes: 1024,
        scanStatus: 'CLEAN',
        downloadUrl: '/api/v1/admin/trainer-applications/app-1/documents/doc-1/download',
        createdAt: '2026-11-08T10:00:00Z',
      },
    ]
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockDocs)

    const res = await trainerApplicationApi.getApplicationDocuments('app-1')

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/trainer-applications/app-1/documents')
    expect(res).toEqual(mockDocs)
    apiRequestSpy.mockRestore()
  })

  it('calls GET /admin/trainer-applications/:id/certificates', async () => {
    const mockCerts = [
      {
        id: 'cert-1',
        name: 'NASM-CPT',
        issuer: 'NASM',
        credentialNumber: '12345',
        issuedOn: '2023-01-01',
        expiresOn: '2025-01-01',
        verificationStatus: 'PENDING',
        documentMediaId: 'doc-1',
        createdAt: '2026-11-08T10:00:00Z',
      },
    ]
    const apiRequestSpy = vi.spyOn(apiClient, 'apiRequest').mockResolvedValueOnce(mockCerts)

    const res = await trainerApplicationApi.getApplicationCertificates('app-1')

    expect(apiRequestSpy).toHaveBeenCalledWith('/admin/trainer-applications/app-1/certificates')
    expect(res).toEqual(mockCerts)
    apiRequestSpy.mockRestore()
  })

  it('calls GET /admin/trainer-applications/:id/documents/:mediaId/download via apiBlobRequest', async () => {
    const mockBlob = new Blob(['sample-data'], { type: 'application/pdf' })
    const apiBlobSpy = vi.spyOn(apiClient, 'apiBlobRequest').mockResolvedValueOnce(mockBlob)

    const res = await trainerApplicationApi.downloadDocument('app-1', 'doc-1')

    expect(apiBlobSpy).toHaveBeenCalledWith('/admin/trainer-applications/app-1/documents/doc-1/download')
    expect(res).toBe(mockBlob)
    apiBlobSpy.mockRestore()
  })
})
