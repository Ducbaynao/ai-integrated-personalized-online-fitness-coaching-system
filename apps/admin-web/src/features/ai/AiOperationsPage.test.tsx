import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AiOperationsPage } from './AiOperationsPage.tsx'
import { aiRunApi } from '../../services/aiRunApi.ts'

vi.mock('../../services/aiRunApi.ts', () => ({
  aiRunApi: {
    getF01FeatureStatus: vi.fn(),
    toggleF01Feature: vi.fn(),
    listRuns: vi.fn(),
  },
}))

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
}

describe('AiOperationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders F01 status and allows toggling with confirmation and reason', async () => {
    vi.mocked(aiRunApi.getF01FeatureStatus).mockResolvedValue({
      featureCode: 'F01_GENERAL_KNOWLEDGE',
      enabled: true,
      updatedBy: 'admin-uuid',
      updatedAt: '2026-10-23T12:00:00Z',
      description: 'General fitness and nutrition knowledge assistance',
    })

    vi.mocked(aiRunApi.listRuns).mockResolvedValue({
      items: [
        {
          id: 'c1d2e3f4-5678-90ab-cdef-1234567890ab',
          status: 'SUCCEEDED',
          requestType: 'F01_GENERAL_KNOWLEDGE',
          purpose: 'General fitness knowledge inquiry',
          correlationId: '00000000-0000-0000-0000-000000000001',
          queuedAt: '2026-10-23T14:30:00Z',
          startedAt: '2026-10-23T14:30:00Z',
          completedAt: '2026-10-23T14:30:01Z',
          latencyMs: 350,
          errorCode: null,
          errorMessage: null,
          inputTokenCount: 120,
          outputTokenCount: 80,
          totalTokenCount: 200,
          evaluationOnly: false,
          createdAt: '2026-10-23T14:30:00Z',
        },
      ],
      total: 1,
      page: 0,
      size: 15,
      totalPages: 1,
    })

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/ai/operations']}>
          <Routes>
            <Route path="/ai/operations" element={<AiOperationsPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    // Verify F01 status display
    expect(await screen.findByText('F01_GENERAL_KNOWLEDGE')).toBeInTheDocument()
    expect(await screen.findByText('ĐANG BẬT (ACTIVE)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tắt tính năng F01' })).toBeInTheDocument()

    // Verify AI Run item display
    expect(await screen.findByText('c1d2e3f4…')).toBeInTheDocument()
    expect(screen.getByText('200 tokens')).toBeInTheDocument()
    expect(screen.getByText('350 ms')).toBeInTheDocument()
    expect(screen.getByText('General fitness knowledge inquiry')).toBeInTheDocument()

    // Click Tắt tính năng F01
    await userEvent.click(screen.getByRole('button', { name: 'Tắt tính năng F01' }))

    // Confirmation dialog appears
    expect(await screen.findByRole('heading', { name: 'Xác nhận TẮT tính năng F01' })).toBeInTheDocument()

    // Type reason
    const reasonInput = screen.getByPlaceholderText('Nhập lý do vận hành hoặc bảo trì...')
    await userEvent.type(reasonInput, 'Testing Kill Switch')

    // Confirm
    vi.mocked(aiRunApi.toggleF01Feature).mockResolvedValue({
      featureCode: 'F01_GENERAL_KNOWLEDGE',
      enabled: false,
      updatedBy: 'admin-uuid',
      updatedAt: '2026-10-23T14:35:00Z',
      description: 'General fitness and nutrition knowledge assistance',
    })

    await userEvent.click(screen.getByRole('button', { name: 'Xác nhận Tắt' }))

    await waitFor(() => {
      expect(aiRunApi.toggleF01Feature).toHaveBeenCalledWith({
        enabled: false,
        reason: 'Testing Kill Switch',
      })
    })
  })

  it('keeps null token usage as null/dash and does not coerce to zero', async () => {
    vi.mocked(aiRunApi.getF01FeatureStatus).mockResolvedValue({
      featureCode: 'F01_GENERAL_KNOWLEDGE',
      enabled: false,
      updatedBy: null,
      updatedAt: '2026-10-23T12:00:00Z',
      description: 'General fitness and nutrition knowledge assistance',
    })

    vi.mocked(aiRunApi.listRuns).mockResolvedValue({
      items: [
        {
          id: 'e2f3a4b5-6789-01cd-ef23-456789012345',
          status: 'FAILED',
          requestType: 'F01_GENERAL_KNOWLEDGE',
          purpose: 'Protein timing inquiry',
          correlationId: '00000000-0000-0000-0000-000000000002',
          queuedAt: '2026-10-23T15:00:00Z',
          startedAt: null,
          completedAt: null,
          latencyMs: null,
          errorCode: 'AI_PROVIDER_ERROR',
          errorMessage: 'Rate limit exceeded',
          inputTokenCount: null,
          outputTokenCount: null,
          totalTokenCount: null,
          evaluationOnly: false,
          createdAt: '2026-10-23T15:00:00Z',
        },
      ],
      total: 1,
      page: 0,
      size: 15,
      totalPages: 1,
    })

    render(
      <QueryClientProvider client={createTestQueryClient()}>
        <MemoryRouter initialEntries={['/ai/operations']}>
          <Routes>
            <Route path="/ai/operations" element={<AiOperationsPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    )

    expect(await screen.findByText('AI_PROVIDER_ERROR')).toBeInTheDocument()
    // Should NOT have "0 tokens" text anywhere
    expect(screen.queryByText('0 tokens')).not.toBeInTheDocument()
  })
})
