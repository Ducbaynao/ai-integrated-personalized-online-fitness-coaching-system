import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TrainerApplicationListPage } from './TrainerApplicationListPage.tsx'
import { TrainerApplicationDetailPage } from './TrainerApplicationDetailPage.tsx'
import { trainerApplicationApi } from '../../services/trainerApplicationApi.ts'
import type { AdminTrainerApplicationResponse } from '../../types/trainerApplication.ts'

vi.mock('../../services/trainerApplicationApi.ts', () => ({
  trainerApplicationApi: {
    getApplications: vi.fn(),
    getApplicationDetail: vi.fn(),
    getApplicationDocuments: vi.fn(),
    getApplicationCertificates: vi.fn(),
    downloadDocument: vi.fn(),
    decideApplication: vi.fn(),
  },
}))

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
}

describe('Trainer Application Pages', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(trainerApplicationApi.getApplicationDocuments).mockResolvedValue([])
    vi.mocked(trainerApplicationApi.getApplicationCertificates).mockResolvedValue([])
  })

  const mockAppPending: AdminTrainerApplicationResponse = {
    id: 'app-pending-1234-5678-90ab',
    trainerId: 'trainer-uuid-1111-2222',
    status: 'PENDING',
    submittedAt: '2026-11-08T10:00:00Z',
    reviewedAt: null,
    reviewedBy: null,
    rejectionReason: null,
    reviewNotes: null,
    applicantNote: 'Certified fitness coach with 5 years experience',
    certificateIds: ['cert-1', 'cert-2'],
    documentMediaIds: ['media-1'],
    createdAt: '2026-11-08T10:00:00Z',
    updatedAt: '2026-11-08T10:00:00Z',
  }

  const mockAppVerified: AdminTrainerApplicationResponse = {
    id: 'app-verified-1234-5678-90ab',
    trainerId: 'trainer-uuid-3333-4444',
    status: 'VERIFIED',
    submittedAt: '2026-11-01T08:00:00Z',
    reviewedAt: '2026-11-02T09:00:00Z',
    reviewedBy: 'admin-1',
    rejectionReason: null,
    reviewNotes: 'Credentials verified successfully',
    applicantNote: 'Master trainer',
    certificateIds: [],
    documentMediaIds: [],
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-02T09:00:00Z',
  }

  describe('TrainerApplicationListPage', () => {
    it('renders list of applications and allows filtering by status', async () => {
      vi.mocked(trainerApplicationApi.getApplications).mockResolvedValue({
        items: [mockAppPending, mockAppVerified],
        page: 0,
        size: 15,
        totalItems: 2,
        totalPages: 1,
      })

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter>
            <TrainerApplicationListPage />
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByText('Chờ xét duyệt')).toBeInTheDocument()
      })

      expect(screen.getByText('Đã phê duyệt')).toBeInTheDocument()

      const user = userEvent.setup()
      const select = screen.getByLabelText('Lọc theo trạng thái')
      await user.selectOptions(select, 'PENDING')

      expect(trainerApplicationApi.getApplications).toHaveBeenCalledWith({
        page: 0,
        size: 15,
        status: 'PENDING',
      })
    })

    it('renders empty state when no applications match', async () => {
      vi.mocked(trainerApplicationApi.getApplications).mockResolvedValue({
        items: [],
        page: 0,
        size: 15,
        totalItems: 0,
        totalPages: 0,
      })

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter>
            <TrainerApplicationListPage />
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByText('Không tìm thấy hồ sơ nào')).toBeInTheDocument()
      })
    })
  })

  describe('TrainerApplicationDetailPage', () => {
    it('renders application details and shows Approve / Reject actions for PENDING application', async () => {
      vi.mocked(trainerApplicationApi.getApplicationDetail).mockResolvedValue(mockAppPending)

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={['/trainer-applications/app-pending-1234-5678-90ab']}>
            <Routes>
              <Route path="/trainer-applications/:applicationId" element={<TrainerApplicationDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByText('Chi tiết hồ sơ xác minh HLV')).toBeInTheDocument()
      })

      expect(screen.getByText('Certified fitness coach with 5 years experience')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Phê duyệt hồ sơ' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Từ chối hồ sơ' })).toBeInTheDocument()
    })

    it('approves an application via confirmation dialog', async () => {
      vi.mocked(trainerApplicationApi.getApplicationDetail).mockResolvedValue(mockAppPending)
      vi.mocked(trainerApplicationApi.decideApplication).mockResolvedValue({
        ...mockAppPending,
        status: 'VERIFIED',
      })

      const user = userEvent.setup()

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={['/trainer-applications/app-pending-1234-5678-90ab']}>
            <Routes>
              <Route path="/trainer-applications/:applicationId" element={<TrainerApplicationDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByRole('button', { name: 'Phê duyệt hồ sơ' })).toBeInTheDocument()
      })

      await user.click(screen.getByRole('button', { name: 'Phê duyệt hồ sơ' }))

      expect(screen.getByText('Phê duyệt hồ sơ huấn luyện viên')).toBeInTheDocument()

      const notesInput = screen.getByLabelText('Ghi chú xét duyệt')
      await user.type(notesInput, 'All credentials verified via registry')

      const confirmBtn = screen.getByRole('button', { name: 'Xác nhận phê duyệt' })
      await user.click(confirmBtn)

      await waitFor(() => {
        expect(trainerApplicationApi.decideApplication).toHaveBeenCalledWith(
          'app-pending-1234-5678-90ab',
          {
            decision: 'APPROVE',
            reviewNotes: 'All credentials verified via registry',
          },
        )
      })

      expect(screen.getByText(/Phê duyệt hồ sơ HLV thành công/)).toBeInTheDocument()
    })

    it('rejects an application requiring a rejection reason', async () => {
      vi.mocked(trainerApplicationApi.getApplicationDetail).mockResolvedValue(mockAppPending)
      vi.mocked(trainerApplicationApi.decideApplication).mockResolvedValue({
        ...mockAppPending,
        status: 'REJECTED',
        rejectionReason: 'Invalid certification format',
      })

      const user = userEvent.setup()

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={['/trainer-applications/app-pending-1234-5678-90ab']}>
            <Routes>
              <Route path="/trainer-applications/:applicationId" element={<TrainerApplicationDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByRole('button', { name: 'Từ chối hồ sơ' })).toBeInTheDocument()
      })

      await user.click(screen.getByRole('button', { name: 'Từ chối hồ sơ' }))

      expect(screen.getByText('Từ chối hồ sơ huấn luyện viên')).toBeInTheDocument()

      const confirmBtn = screen.getByRole('button', { name: 'Xác nhận từ chối' })
      expect(confirmBtn).toBeDisabled()

      const reasonInput = screen.getByLabelText('Lý do từ chối')
      await user.type(reasonInput, 'Certification expired and document scan is unreadable.')

      expect(confirmBtn).not.toBeDisabled()
      await user.click(confirmBtn)

      await waitFor(() => {
        expect(trainerApplicationApi.decideApplication).toHaveBeenCalledWith(
          'app-pending-1234-5678-90ab',
          {
            decision: 'REJECT',
            rejectionReason: 'Certification expired and document scan is unreadable.',
            reviewNotes: undefined,
          },
        )
      })

      expect(screen.getByText(/Đã từ chối hồ sơ HLV và ghi nhận lý do từ chối/)).toBeInTheDocument()
    })

    it('does not display decision action buttons when application is already VERIFIED', async () => {
      vi.mocked(trainerApplicationApi.getApplicationDetail).mockResolvedValue(mockAppVerified)

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={['/trainer-applications/app-verified-1234-5678-90ab']}>
            <Routes>
              <Route path="/trainer-applications/:applicationId" element={<TrainerApplicationDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByText('Chi tiết hồ sơ xác minh HLV')).toBeInTheDocument()
      })

      expect(screen.queryByRole('button', { name: 'Phê duyệt hồ sơ' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Từ chối hồ sơ' })).not.toBeInTheDocument()
    })

    it('renders detailed certificates and documents table and supports document download', async () => {
      vi.mocked(trainerApplicationApi.getApplicationDetail).mockResolvedValue(mockAppPending)
      vi.mocked(trainerApplicationApi.getApplicationCertificates).mockResolvedValue([
        {
          id: 'cert-1',
          name: 'NASM-CPT Certified Personal Trainer',
          issuer: 'NASM',
          credentialNumber: 'CERT-9988',
          issuedOn: '2023-05-10',
          expiresOn: '2025-05-10',
          verificationStatus: 'PENDING',
          documentMediaId: 'media-1',
          createdAt: '2026-11-08T10:00:00Z',
        },
      ])
      vi.mocked(trainerApplicationApi.getApplicationDocuments).mockResolvedValue([
        {
          id: 'media-1',
          originalFilename: 'nasm-cert.pdf',
          contentType: 'application/pdf',
          sizeBytes: 2048576,
          scanStatus: 'CLEAN',
          downloadUrl: '/api/v1/admin/trainer-applications/app-pending-1234-5678-90ab/documents/media-1/download',
          createdAt: '2026-11-08T10:00:00Z',
        },
      ])

      const mockBlob = new Blob(['pdf-content'], { type: 'application/pdf' })
      vi.mocked(trainerApplicationApi.downloadDocument).mockResolvedValue(mockBlob)

      const createObjectURLMock = vi.fn().mockReturnValue('blob:http://localhost/dummy')
      const revokeObjectURLMock = vi.fn()
      window.URL.createObjectURL = createObjectURLMock
      window.URL.revokeObjectURL = revokeObjectURLMock

      const user = userEvent.setup()

      render(
        <QueryClientProvider client={createTestQueryClient()}>
          <MemoryRouter initialEntries={['/trainer-applications/app-pending-1234-5678-90ab']}>
            <Routes>
              <Route path="/trainer-applications/:applicationId" element={<TrainerApplicationDetailPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      )

      await waitFor(() => {
        expect(screen.getByText('NASM-CPT Certified Personal Trainer')).toBeInTheDocument()
      })

      expect(screen.getByText('CERT-9988')).toBeInTheDocument()
      expect(screen.getByText('nasm-cert.pdf')).toBeInTheDocument()
      expect(screen.getByText('application/pdf')).toBeInTheDocument()
      expect(screen.getByText('1.95 MB')).toBeInTheDocument()
      expect(screen.getByText('Đã quét sạch (CLEAN)')).toBeInTheDocument()

      const downloadBtn = screen.getByRole('button', { name: 'Tải xuống nasm-cert.pdf' })
      expect(downloadBtn).toBeInTheDocument()

      await user.click(downloadBtn)

      await waitFor(() => {
        expect(trainerApplicationApi.downloadDocument).toHaveBeenCalledWith(
          'app-pending-1234-5678-90ab',
          'media-1',
        )
      })

      expect(createObjectURLMock).toHaveBeenCalledWith(mockBlob)
      expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:http://localhost/dummy')
    })
  })
})
