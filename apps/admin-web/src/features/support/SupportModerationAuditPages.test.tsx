import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SupportCaseListPage } from './SupportCaseListPage.tsx'
import { SupportCaseDetailPage } from './SupportCaseDetailPage.tsx'
import { ModerationCaseListPage } from '../moderation/ModerationCaseListPage.tsx'
import { PrivilegedAccessPage } from '../privileged-access/PrivilegedAccessPage.tsx'
import { DataCorrectionListPage } from '../data-correction/DataCorrectionListPage.tsx'
import { AuditLogListPage } from '../audit/AuditLogListPage.tsx'
import {
  auditApi,
  dataCorrectionApi,
  moderationApi,
  privilegedAccessApi,
  supportApi,
} from '../../services/supportModerationAuditApi.ts'

vi.mock('../../services/supportModerationAuditApi.ts', () => ({
  supportApi: {
    getSupportCases: vi.fn(),
    getSupportCaseDetail: vi.fn(),
    createSupportCase: vi.fn(),
    updateStatus: vi.fn(),
    assignCase: vi.fn(),
    addNote: vi.fn(),
  },
  moderationApi: {
    getModerationCases: vi.fn(),
    getModerationCaseDetail: vi.fn(),
    createModerationCase: vi.fn(),
    updateStatus: vi.fn(),
    performAction: vi.fn(),
  },
  privilegedAccessApi: {
    getRequests: vi.fn(),
    getActive: vi.fn(),
    getRequestDetail: vi.fn(),
    requestAccess: vi.fn(),
    revokeAccess: vi.fn(),
  },
  dataCorrectionApi: {
    getCorrections: vi.fn(),
    getCorrectionDetail: vi.fn(),
    executeCorrection: vi.fn(),
  },
  auditApi: {
    getAuditLogs: vi.fn(),
    getAuditLogDetail: vi.fn(),
  },
}))

describe('Support, Moderation, Privileged Access, Data Correction, and Audit Pages', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders SupportCaseListPage with cases table', async () => {
    vi.mocked(supportApi.getSupportCases).mockResolvedValueOnce({
      content: [
        {
          id: 'case-1',
          caseNumber: 'SUP-001',
          requesterId: 'req-1',
          requesterName: 'Nguyễn Văn A',
          assignedToAdminId: null,
          assignedToAdminName: null,
          subject: 'Không sửa được số đo',
          category: 'ACCOUNT',
          status: 'OPEN',
          priority: 'HIGH',
          openedAt: '2026-11-11T00:00:00Z',
          resolvedAt: null,
          closedAt: null,
          updatedAt: '2026-11-11T00:00:00Z',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 15,
    })

    render(
      <MemoryRouter>
        <SupportCaseListPage />
      </MemoryRouter>,
    )

    expect(screen.getByText('Đang tải danh sách phiếu hỗ trợ...')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('SUP-001')).toBeInTheDocument()
      expect(screen.getByText('Không sửa được số đo')).toBeInTheDocument()
      expect(screen.getByText('Nguyễn Văn A')).toBeInTheDocument()
    })
  })

  it('renders SupportCaseDetailPage with timeline and actions', async () => {
    vi.mocked(supportApi.getSupportCaseDetail).mockResolvedValueOnce({
      summary: {
        id: 'case-1',
        caseNumber: 'SUP-001',
        requesterId: 'req-1',
        requesterName: 'Nguyễn Văn A',
        assignedToAdminId: null,
        assignedToAdminName: null,
        subject: 'Cần hỗ trợ số đo cân nặng',
        category: 'ACCOUNT',
        status: 'OPEN',
        priority: 'MEDIUM',
        openedAt: '2026-11-11T00:00:00Z',
        resolvedAt: null,
        closedAt: null,
        updatedAt: '2026-11-11T00:00:00Z',
      },
      events: [
        {
          id: 'ev-1',
          eventType: 'CASE_CREATED',
          actorUserId: 'req-1',
          actorName: 'Nguyễn Văn A',
          notes: 'Mở phiếu hỗ trợ',
          payloadJson: null,
          createdAt: '2026-11-11T00:00:00Z',
        },
      ],
    })

    render(
      <MemoryRouter initialEntries={['/support-cases/case-1']}>
        <Routes>
          <Route path="/support-cases/:caseId" element={<SupportCaseDetailPage />} />
        </Routes>
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('Phiếu hỗ trợ #SUP-001')).toBeInTheDocument()
      expect(screen.getByText('CASE_CREATED')).toBeInTheDocument()
      expect(screen.getByText('Cập nhật trạng thái')).toBeInTheDocument()
      expect(screen.getByText('Lưu ghi chú')).toBeInTheDocument()
    })
  })

  it('renders ModerationCaseListPage and DetailPage', async () => {
    vi.mocked(moderationApi.getModerationCases).mockResolvedValueOnce({
      content: [
        {
          id: 'mod-1',
          targetType: 'USER',
          targetId: 'u-1',
          targetDisplayName: 'Học viên Vi phạm',
          assignedToAdminId: null,
          assignedToAdminName: null,
          status: 'OPEN',
          priority: 'HIGH',
          createdReason: 'Ngôn từ xúc phạm',
          notes: null,
          openedAt: '2026-11-11T00:00:00Z',
          resolvedAt: null,
          updatedAt: '2026-11-11T00:00:00Z',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 15,
    })

    render(
      <MemoryRouter>
        <ModerationCaseListPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('Học viên Vi phạm')).toBeInTheDocument()
      expect(screen.getByText('Ngôn từ xúc phạm')).toBeInTheDocument()
    })
  })

  it('renders PrivilegedAccessPage with requests', async () => {
    vi.mocked(privilegedAccessApi.getRequests).mockResolvedValueOnce({
      content: [
        {
          id: 'priv-1',
          requestedByAdminId: 'adm-1',
          requestedByAdminName: 'Admin Alpha',
          approvedByAdminId: 'adm-1',
          approvedByAdminName: 'Admin Alpha',
          targetUserId: 'stu-1',
          targetUserName: 'Lê Thị D',
          purpose: 'DATA_CORRECTION',
          accessScopeJson: '{}',
          reason: 'Sửa số đo cân nặng',
          relatedSupportCaseId: null,
          supportCaseNumber: null,
          status: 'ACTIVE',
          requestedAt: '2026-11-11T00:00:00Z',
          approvedAt: '2026-11-11T00:00:00Z',
          openedAt: '2026-11-11T00:00:00Z',
          expiresAt: '2026-11-11T01:00:00Z',
          revokedAt: null,
          currentlyActive: true,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 15,
    })

    render(
      <MemoryRouter>
        <PrivilegedAccessPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('DATA_CORRECTION')).toBeInTheDocument()
      expect(screen.getByText('Lê Thị D')).toBeInTheDocument()
      expect(screen.getByText('Thu hồi')).toBeInTheDocument()
    })
  })

  it('renders DataCorrectionListPage with history', async () => {
    vi.mocked(dataCorrectionApi.getCorrections).mockResolvedValueOnce({
      content: [
        {
          id: 'corr-1',
          supportCaseId: 'case-1',
          caseNumber: 'SUP-001',
          targetType: 'MEASUREMENT',
          targetId: 'meas-1',
          beforeDataJson: '{"value":75.0}',
          proposedDataJson: '{"value":72.0}',
          appliedDataJson: '{"value":72.0}',
          validationResultJson: '{"status":"VALID"}',
          requestedBy: 'adm-1',
          requestedByName: 'Admin Alpha',
          appliedBy: 'adm-1',
          appliedByName: 'Admin Alpha',
          createdAt: '2026-11-11T00:00:00Z',
          appliedAt: '2026-11-11T00:00:00Z',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 15,
    })

    render(
      <MemoryRouter>
        <DataCorrectionListPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('MEASUREMENT')).toBeInTheDocument()
      expect(screen.getByText('SUP-001')).toBeInTheDocument()
      expect(screen.getByText('Xem biến động dữ liệu')).toBeInTheDocument()
    })
  })

  it('renders AuditLogListPage with logs', async () => {
    vi.mocked(auditApi.getAuditLogs).mockResolvedValueOnce({
      content: [
        {
          id: 'log-1',
          actorUserId: 'adm-1',
          actorRole: 'ADMIN',
          actorName: 'Admin Alpha',
          action: 'ADMIN_DATA_CORRECTION',
          targetType: 'MEASUREMENT',
          targetId: 'meas-1',
          beforeDataJson: '{"value":75.0}',
          afterDataJson: '{"value":72.0}',
          metadataJson: '{}',
          ipAddress: '127.0.0.1',
          userAgent: 'Mozilla/5.0',
          occurredAt: '2026-11-11T00:00:00Z',
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    })

    render(
      <MemoryRouter>
        <AuditLogListPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('ADMIN_DATA_CORRECTION')).toBeInTheDocument()
      expect(screen.getByText('Admin Alpha')).toBeInTheDocument()
      expect(screen.getByText('Xem JSON')).toBeInTheDocument()
    })
  })
})
