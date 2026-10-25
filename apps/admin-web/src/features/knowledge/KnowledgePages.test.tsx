import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { KnowledgeListPage } from './KnowledgeListPage.tsx'
import { KnowledgeDetailPage } from './KnowledgeDetailPage.tsx'
import { knowledgeApi } from '../../services/knowledgeApi.ts'

vi.mock('../../services/knowledgeApi.ts', () => ({
  knowledgeApi: {
    listDocuments: vi.fn(),
    getDocument: vi.fn(),
    listVersions: vi.fn(),
    reviewVersion: vi.fn(),
    publishVersion: vi.fn(),
    archiveVersion: vi.fn(),
  },
}))

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
}

describe('KnowledgeListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders list of knowledge documents', async () => {
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([
      {
        id: 'doc-1',
        documentCode: 'ACSM-RESISTANCE-2024',
        title: 'ACSM Guidelines for Strength Training',
        sourceId: 'acsm-src-1',
        sourceName: 'ACSM Official Guidelines',
        sourceUrl: 'https://acsm.org/guidelines',
        author: 'American College of Sports Medicine',
        documentType: 'CLINICAL_GUIDELINE',
        language: 'en',
        createdAt: '2026-01-15T00:00:00Z',
      },
    ])

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/knowledge']}>
          <Routes>
            <Route path="/knowledge" element={<KnowledgeListPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    expect(await screen.findByText('ACSM Guidelines for Strength Training')).toBeInTheDocument()
    expect(screen.getByText('ACSM-RESISTANCE-2024')).toBeInTheDocument()
    expect(screen.getByText('Tác giả: American College of Sports Medicine')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem chi tiết ACSM-RESISTANCE-2024' })).toBeInTheDocument()
  })

  it('shows empty state when no documents exist', async () => {
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([])

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/knowledge']}>
          <Routes>
            <Route path="/knowledge" element={<KnowledgeListPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    expect(await screen.findByText('Chưa có tài liệu tri thức nào')).toBeInTheDocument()
  })
})

describe('KnowledgeDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders document details and version actions', async () => {
    vi.mocked(knowledgeApi.getDocument).mockResolvedValue({
      id: 'doc-1',
      documentCode: 'ACSM-RESISTANCE-2024',
      title: 'ACSM Guidelines for Strength Training',
      sourceId: 'acsm-src-1',
      sourceName: 'ACSM Official Guidelines',
      sourceUrl: 'https://acsm.org/guidelines',
      author: 'ACSM',
      documentType: 'GUIDELINE',
      language: 'en',
      createdAt: '2026-01-15T00:00:00Z',
    })

    vi.mocked(knowledgeApi.listVersions).mockResolvedValue([
      {
        id: 'ver-1',
        knowledgeDocumentId: 'doc-1',
        versionNumber: 1,
        versionLabel: 'v1.0.0',
        status: 'PENDING_REVIEW',
        technicalReadiness: 'READY',
        holdReason: null,
        contentHash: 'a1b2c3d4e5f6',
        contentPath: 's3://corpus/v1.json',
        sourceBytes: 2048,
        reviewEvidenceId: null,
        lineageBaseDocumentId: null,
        lineageBaseVersion: null,
        content: null,
        reviewedBy: null,
        reviewedAt: null,
        publishedBy: null,
        publishedAt: null,
        activatedAt: null,
        archivedAt: null,
        createdAt: '2026-01-15T01:00:00Z',
      },
    ])

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/knowledge/doc-1']}>
          <Routes>
            <Route path="/knowledge/:documentId" element={<KnowledgeDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    expect(await screen.findByRole('heading', { name: 'ACSM Guidelines for Strength Training' })).toBeInTheDocument()
    expect(screen.getByText('v1.0.0')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Duyệt (Approve)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Từ chối' })).toBeInTheDocument()

    // Click Duyệt -> opens confirmation dialog
    await userEvent.click(screen.getByRole('button', { name: 'Duyệt (Approve)' }))
    expect(await screen.findByRole('heading', { name: 'Chấp thuận phiên bản v1.0.0' })).toBeInTheDocument()

    // Confirm dialog
    vi.mocked(knowledgeApi.reviewVersion).mockResolvedValue(undefined)
    await userEvent.click(screen.getByRole('button', { name: 'Xác nhận Duyệt' }))

    await waitFor(() => {
      expect(knowledgeApi.reviewVersion).toHaveBeenCalledWith('ver-1', {
        decision: 'APPROVED',
        notes: undefined,
      })
    })
  })
})
