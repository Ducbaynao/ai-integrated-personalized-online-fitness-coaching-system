import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { KnowledgeListPage } from './KnowledgeListPage.tsx'
import { KnowledgeDetailPage } from './KnowledgeDetailPage.tsx'
import { knowledgeApi } from '../../services/knowledgeApi.ts'
import { ApiError } from '../../services/apiClient.ts'
import { useAuth } from '../auth/authContextValue.ts'
import { createCurrentUser } from '../../test/testData.ts'

vi.mock('../auth/authContextValue.ts', () => ({ useAuth: vi.fn() }))

vi.mock('../../services/knowledgeApi.ts', () => ({
  knowledgeApi: {
    listDocuments: vi.fn(),
    importCorpus: vi.fn(),
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
    vi.mocked(useAuth).mockReturnValue({ status: 'authenticated',
      user: createCurrentUser({ roles: ['ADMIN'], permissions: ['KNOWLEDGE_IMPORT'] }),
      signIn: vi.fn(), signOut: vi.fn(), retryBootstrap: vi.fn() })
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

  it('only offers import with KNOWLEDGE_IMPORT', async () => {
    vi.mocked(useAuth).mockReturnValue({ status: 'authenticated',
      user: createCurrentUser({ roles: ['ADMIN'], permissions: [] }),
      signIn: vi.fn(), signOut: vi.fn(), retryBootstrap: vi.fn() })
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([])
    render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    await screen.findByText('Chưa có tài liệu tri thức nào')
    expect(screen.queryByRole('button', { name: 'Import bundle' })).not.toBeInTheDocument()
    expect(knowledgeApi.importCorpus).not.toHaveBeenCalled()
  })

  it.each([401, 403])('conceals the import form when the list read fails with HTTP %i', async (status) => {
    vi.mocked(knowledgeApi.listDocuments).mockRejectedValue(new ApiError(status, 'DENIED'))
    render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    expect(await screen.findByText('Không thể tải danh sách tài liệu tri thức')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import bundle' })).not.toBeInTheDocument()
    expect(knowledgeApi.importCorpus).not.toHaveBeenCalled()
  })

  it('imports a JSON bundle and shows imported and skipped results without publishing', async () => {
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([])
    vi.mocked(knowledgeApi.importCorpus).mockResolvedValue({ totalItems: 3, importedCount: 1, skippedCount: 2,
      importedItems: [{ documentId: 'doc-1', versionId: 'ver-1', documentCode: 'DOC-1', versionLabel: 'v1',
        status: 'DRAFT', technicalReadiness: 'DRAFT_STAGED' }], skippedItems: ['DOC-2:v1', 'DOC-2:v1'] })
    render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    const bundle = { bundleName: 'pilot', items: [
      { documentCode: 'DOC-1', sourceId: 'source-1', title: 'Strength', versionLabel: 'v1', contentHash: 'abc', content: 'content' },
      { documentCode: 'DOC-2', sourceId: 'source-2', title: 'Recovery', versionLabel: 'v1', contentHash: 'def', content: 'more content' },
    ] }
    fireEvent.change(screen.getByLabelText('Bundle JSON'), { target: { value: JSON.stringify(bundle) } })
    await userEvent.click(screen.getByRole('button', { name: 'Import bundle' }))
    await waitFor(() => expect(vi.mocked(knowledgeApi.importCorpus).mock.calls[0][0]).toEqual(bundle))
    expect(await screen.findByText(/1 imported, 2 skipped \/ 3 mục/)).toBeInTheDocument()
    expect(screen.getByText(/DOC-1 · v1 · DRAFT · DRAFT_STAGED/)).toBeInTheDocument()
    const skippedElements = screen.getAllByText('DOC-2:v1')
    expect(skippedElements).toHaveLength(2)
    expect(knowledgeApi.publishVersion).not.toHaveBeenCalled()
  })

  it('preserves access denied banner after 403 even if auth state removes permission', async () => {
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([])
    vi.mocked(knowledgeApi.importCorpus).mockRejectedValue(new ApiError(403, 'FORBIDDEN'))
    const { rerender } = render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    const bundle = { items: [{ documentCode: 'DOC-1', sourceId: 'source-1', title: 'Strength',
      versionLabel: 'v1', contentHash: 'abc', content: 'content' }] }
    fireEvent.change(screen.getByLabelText('Bundle JSON'), { target: { value: JSON.stringify(bundle) } })
    await userEvent.click(screen.getByRole('button', { name: 'Import bundle' }))
    expect(await screen.findByText(/Đã dừng thao tác import/)).toBeInTheDocument()

    // simulate auth update losing KNOWLEDGE_IMPORT permission
    vi.mocked(useAuth).mockReturnValue({
      status: 'authenticated',
      user: createCurrentUser({ roles: ['ADMIN'], permissions: [] }),
      signIn: vi.fn(), signOut: vi.fn(), retryBootstrap: vi.fn(),
    })
    rerender(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    expect(screen.getByText(/Đã dừng thao tác import/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import bundle' })).not.toBeInTheDocument()
  })

  it('does not resend an import after timeout and stops the action after 403', async () => {
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([])
    vi.mocked(knowledgeApi.importCorpus).mockRejectedValueOnce(new ApiError(504, 'TIMEOUT'))
      .mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN'))
    render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    const bundle = { items: [{ documentCode: 'DOC-1', sourceId: 'source-1', title: 'Strength',
      versionLabel: 'v1', contentHash: 'abc', content: 'content' }] }
    fireEvent.change(screen.getByLabelText('Bundle JSON'), { target: { value: JSON.stringify(bundle) } })
    await userEvent.click(screen.getByRole('button', { name: 'Import bundle' }))
    expect(await screen.findByText(/Chưa rõ kết quả import/)).toBeInTheDocument()
    expect(knowledgeApi.importCorpus).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: 'Import bundle' }))
    expect(await screen.findByText(/Đã dừng thao tác import/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import bundle' })).not.toBeInTheDocument()
    expect(knowledgeApi.importCorpus).toHaveBeenCalledTimes(2)
  })

  it('reports a 409 conflict without retrying the import', async () => {
    vi.mocked(knowledgeApi.listDocuments).mockResolvedValue([])
    vi.mocked(knowledgeApi.importCorpus).mockRejectedValue(new ApiError(409, 'CONTENT_CONFLICT'))
    render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <KnowledgeListPage />
    </MemoryRouter></QueryClientProvider>)
    fireEvent.change(screen.getByLabelText('Bundle JSON'), { target: { value: JSON.stringify({ items: [
      { documentCode: 'DOC-1', sourceId: 'source-1', title: 'Strength', versionLabel: 'v1', contentHash: 'abc', content: 'content' },
    ] }) } })
    await userEvent.click(screen.getByRole('button', { name: 'Import bundle' }))
    expect(await screen.findByText(/Phiên bản hoặc thông tin nguồn xung đột/)).toBeInTheDocument()
    expect(knowledgeApi.importCorpus).toHaveBeenCalledTimes(1)
  })

  it('paginates by page and size and preserves the page through detail navigation', async () => {
    const documents = Array.from({ length: 50 }, (_, index) => ({ id: `doc-${index}`, documentCode: `DOC-${index}`,
      title: `Title ${index}`, sourceId: 'source', sourceName: 'Source', sourceUrl: '', author: '',
      documentType: 'GUIDELINE', language: 'vi', createdAt: '2026-01-15T00:00:00Z' }))
    vi.mocked(knowledgeApi.listDocuments).mockImplementation(async (page) => page === 0 ? documents : [documents[0]])
    vi.mocked(knowledgeApi.getDocument).mockResolvedValue(documents[0])
    vi.mocked(knowledgeApi.listVersions).mockResolvedValue([])
    render(<QueryClientProvider client={createTestQueryClient()}><MemoryRouter initialEntries={['/knowledge']}>
      <Routes><Route path="/knowledge" element={<KnowledgeListPage />} />
        <Route path="/knowledge/:documentId" element={<KnowledgeDetailPage />} /></Routes>
    </MemoryRouter></QueryClientProvider>)
    expect(await screen.findByText('Title 49')).toBeInTheDocument()
    expect(knowledgeApi.listDocuments).toHaveBeenCalledWith(0, 50)
    await userEvent.click(screen.getByRole('button', { name: 'Trang kế' }))
    expect(await screen.findByText('Trang 2')).toBeInTheDocument()
    expect(knowledgeApi.listDocuments).toHaveBeenCalledWith(1, 50)
    expect(screen.getByRole('button', { name: 'Trang kế' })).toBeDisabled()
    await userEvent.click(screen.getByRole('link', { name: 'Xem chi tiết DOC-0' }))
    await userEvent.click(await screen.findByRole('link', { name: /Danh sách tài liệu/ }))
    expect(await screen.findByText('Trang 2')).toBeInTheDocument()
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

  it('renders Publish button for DRAFT version and handles confirmation', async () => {
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
        id: 'ver-draft',
        knowledgeDocumentId: 'doc-1',
        versionNumber: 2,
        versionLabel: 'v2.0.0',
        status: 'DRAFT',
        technicalReadiness: 'TECHNICAL_VERIFICATION_PASSED',
        holdReason: null,
        contentHash: 'hash-draft-2',
        contentPath: 's3://corpus/v2.json',
        sourceBytes: 4096,
        reviewEvidenceId: null,
        lineageBaseDocumentId: null,
        lineageBaseVersion: null,
        content: null,
        reviewedBy: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        reviewedAt: '2026-01-15T01:00:00Z',
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

    expect(await screen.findByRole('button', { name: 'Công bố (Publish)' })).toBeInTheDocument()

    // Click Publish -> opens confirmation dialog
    await userEvent.click(screen.getByRole('button', { name: 'Công bố (Publish)' }))
    expect(await screen.findByRole('heading', { name: 'Công bố phiên bản v2.0.0' })).toBeInTheDocument()

    // Confirm dialog
    vi.mocked(knowledgeApi.publishVersion).mockResolvedValue(undefined)
    await userEvent.click(screen.getByRole('button', { name: 'Công bố ngay' }))

    await waitFor(() => {
      expect(knowledgeApi.publishVersion).toHaveBeenCalledWith('ver-draft')
    })
  })

  it('renders Archive button for ACTIVE version and handles confirmation with reason', async () => {
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
        id: 'ver-active',
        knowledgeDocumentId: 'doc-1',
        versionNumber: 1,
        versionLabel: 'v1.0.0',
        status: 'ACTIVE',
        technicalReadiness: 'TECHNICAL_VERIFICATION_PASSED',
        holdReason: null,
        contentHash: 'hash-active-1',
        contentPath: 's3://corpus/v1.json',
        sourceBytes: 2048,
        reviewEvidenceId: null,
        lineageBaseDocumentId: null,
        lineageBaseVersion: null,
        content: null,
        reviewedBy: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        reviewedAt: '2026-01-15T01:00:00Z',
        publishedBy: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        publishedAt: '2026-01-15T02:00:00Z',
        activatedAt: '2026-01-15T02:00:00Z',
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

    expect(await screen.findByRole('button', { name: 'Lưu trữ (Archive)' })).toBeInTheDocument()

    // Click Archive -> opens confirmation dialog
    await userEvent.click(screen.getByRole('button', { name: 'Lưu trữ (Archive)' }))
    expect(await screen.findByRole('heading', { name: 'Lưu trữ phiên bản v1.0.0' })).toBeInTheDocument()

    // Enter reason and confirm
    await userEvent.type(screen.getByLabelText('Ghi chú / Lý do quyết định:'), 'Thay thế bằng phiên bản mới')
    vi.mocked(knowledgeApi.archiveVersion).mockResolvedValue(undefined)
    await userEvent.click(screen.getByRole('button', { name: 'Xác nhận Lưu trữ' }))

    await waitFor(() => {
      expect(knowledgeApi.archiveVersion).toHaveBeenCalledWith('ver-active', {
        reason: 'Thay thế bằng phiên bản mới',
      })
    })
  })
})
