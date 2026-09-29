import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exerciseQueryKeys } from '../../services/exerciseApi.ts'
import { createExerciseDetail } from '../../test/exerciseTestData.ts'
import { ExerciseDetailPage } from './ExerciseDetailPage.tsx'

const EXERCISE_ID = '2c5f9430-c360-4b32-b70a-d6f92b76bfd4'

function renderDetail(
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } }),
  initialEntry: string | { pathname: string; state?: unknown } = `/exercises/${EXERCISE_ID}`,
) {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/exercises/:exerciseId" element={<ExerciseDetailPage />} />
          <Route path="/exercises" element={<h1>Danh sách kiểm thử</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return queryClient
}

describe('exercise detail', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders API content unchanged with accessible semantic sections', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExerciseDetail({ canonicalReplacementId: EXERCISE_ID }))))
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Barbell Squat' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Nội dung hướng dẫn' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'High-bar Squat' })).toBeInTheDocument()
    expect(screen.getByText('Upright squat variation.')).toBeInTheDocument()
    expect(screen.getByText(/Place the bar above/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Bài tập thay thế chuẩn' })).toBeInTheDocument()
  })

  it.each([
    [401, 'SESSION_REVOKED', 'Phiên đăng nhập đã hết hạn'],
    [403, 'CATALOG_MANAGE_REQUIRED', 'Bạn không có quyền truy cập'],
    [404, 'ADMIN_EXERCISE_NOT_FOUND', 'Không tìm thấy bài tập'],
  ])('hides cached detail after terminal HTTP %s without exposing raw messages', async (status, errorCode, expectedTitle) => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
    queryClient.setQueryData(exerciseQueryKeys.detail(EXERCISE_ID), createExerciseDetail())
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => Response.json(
      { errorCode, message: 'secret archived record' },
      { status },
    )))
    renderDetail(queryClient)

    expect(await screen.findByRole('heading', { name: expectedTitle })).toBeInTheDocument()
    expect(screen.queryByText('Barbell Squat')).not.toBeInTheDocument()
    expect(screen.queryByText('secret archived record')).not.toBeInTheDocument()
  })

  it('keeps cached detail only for a transient network error', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
    queryClient.setQueryData(exerciseQueryKeys.detail(EXERCISE_ID), createExerciseDetail())
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline raw message')))
    renderDetail(queryClient)

    expect(screen.getByRole('heading', { name: 'Barbell Squat' })).toBeInTheDocument()
    expect(await screen.findByText(/Chi tiết gần nhất vẫn đang được hiển thị/)).toBeInTheDocument()
    expect(screen.queryByText('offline raw message')).not.toBeInTheDocument()
  })

  it('returns to the originating list URL state', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExerciseDetail())))
    const user = userEvent.setup()
    renderDetail(undefined, {
      pathname: `/exercises/${EXERCISE_ID}`,
      state: { from: '/exercises?status=ARCHIVED&page=2' },
    })
    await screen.findByRole('heading', { name: 'Barbell Squat' })

    await user.click(screen.getByRole('button', { name: '← Quay lại danh sách' }))
    expect(screen.getByRole('heading', { name: 'Danh sách kiểm thử' })).toBeInTheDocument()
  })

  it('rejects an invalid route locally without making an API request', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    renderDetail(undefined, '/exercises/not-a-uuid')

    expect(await screen.findByRole('heading', { name: 'Đường dẫn bài tập không hợp lệ' })).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
