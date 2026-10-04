import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exerciseQueryKeys } from '../../services/exerciseApi.ts'
import { createExercisePage, createExerciseSummary } from '../../test/exerciseTestData.ts'
import { ExerciseListPage } from './ExerciseListPage.tsx'

function renderList(initialPath = '/exercises') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/exercises" element={<ExerciseListPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('exercise catalog list', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders Vietnamese interface copy while preserving the API exercise name', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExercisePage())))
    renderList()

    expect(await screen.findByRole('link', { name: 'Barbell Squat' })).toBeInTheDocument()
    expect(screen.getAllByText('Đang hoạt động')).toHaveLength(2)
    expect(screen.getByRole('columnheader', { name: 'Tên bài tập' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Nhóm cơ' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Thiết bị' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Tư liệu khả dụng' })).toBeInTheDocument()
    expect(screen.getByText('QUADRICEPS')).toBeInTheDocument()
    expect(screen.getByText('BARBELL')).toBeInTheDocument()
    expect(screen.getByText('Chưa có tư liệu khả dụng')).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: 'Tìm kiếm' })).toHaveAttribute(
      'placeholder',
      'Tìm theo tên hoặc mã bài tập',
    )
    expect(screen.getByRole('link', { name: 'Tạo bài tập' })).toHaveAttribute('href', '/exercises/new')
    expect(screen.queryByRole('link', { name: 'Chỉnh sửa' })).not.toBeInTheDocument()
  })

  it('renders known missing relationships explicitly instead of inventing counts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExercisePage({
      items: [createExerciseSummary({
        muscleGroupCodes: [],
        equipmentCodes: [],
        mediaAvailable: false,
      })],
    }))))
    renderList()

    expect(await screen.findByRole('link', { name: 'Barbell Squat' })).toBeInTheDocument()
    expect(screen.getAllByText('Chưa có dữ liệu')).toHaveLength(2)
    expect(screen.getByText('Chưa có tư liệu khả dụng')).toBeInTheDocument()
  })

  it('shows edit only for DRAFT rows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExercisePage({
      items: [createExercisePage().items[0], {
        ...createExercisePage().items[0],
        id: '10000000-0000-0000-0000-000000000001',
        code: 'DRAFT_SQUAT',
        name: 'Draft Squat',
        status: 'DRAFT',
      }],
      totalItems: 2,
    }))))
    renderList()

    expect(await screen.findByRole('link', { name: 'Chỉnh sửa' })).toHaveAttribute(
      'href',
      '/exercises/10000000-0000-0000-0000-000000000001/edit',
    )
    expect(screen.getAllByText('Chỉ xem')).toHaveLength(1)
  })

  it('announces the initial loading state accessibly', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)))
    renderList()

    expect(screen.getByLabelText('Đang tải danh sách bài tập')).toBeInTheDocument()
    expect(screen.getByRole('search', { name: 'Tìm và lọc bài tập' })).toBeInTheDocument()
  })

  it.each([
    ['network', () => Promise.reject(new TypeError('raw network details')), 'Không thể kết nối đến hệ thống'],
    ['server', () => Promise.resolve(Response.json(
      { errorCode: 'INTERNAL_ERROR', message: 'raw server details' },
      { status: 503 },
    )), 'Hệ thống đang gặp sự cố'],
  ])('maps an initial %s failure to safe Vietnamese copy', async (_kind, responseFactory, expectedTitle) => {
    vi.stubGlobal('fetch', vi.fn(responseFactory))
    renderList()

    expect(await screen.findByRole('heading', { name: expectedTitle })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeInTheDocument()
    expect(screen.queryByText(/raw (network|server) details/)).not.toBeInTheDocument()
  })

  it('distinguishes an empty catalog from a filtered no-result state', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => Response.json(
      createExercisePage({ items: [], totalItems: 0, totalPages: 0 }),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const { unmount } = renderList()
    expect(await screen.findByRole('heading', { name: 'Danh mục chưa có bài tập' })).toBeInTheDocument()
    unmount()

    renderList('/exercises?query=missing')
    expect(await screen.findByRole('heading', { name: 'Không có kết quả phù hợp' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xóa bộ lọc' })).toBeInTheDocument()
  })

  it('searches, filters lifecycle, and paginates with the backend contract', async () => {
    const fetchMock = vi.fn().mockImplementation(async () => Response.json(
      createExercisePage({ totalItems: 40, totalPages: 2 }),
    ))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderList()
    await screen.findByRole('link', { name: 'Barbell Squat' })

    await user.type(screen.getByRole('searchbox', { name: 'Tìm kiếm' }), 'press')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('query=press'),
      expect.anything(),
    ), { timeout: 1500 })

    fireEvent.change(screen.getByRole('combobox', { name: 'Trạng thái' }), { target: { value: 'ARCHIVED' } })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('status=ARCHIVED'),
      expect.anything(),
    ))
    await screen.findByRole('link', { name: 'Barbell Squat' })

    await user.click(screen.getByRole('button', { name: 'Trang sau' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('page=1'),
      expect.anything(),
    ))
  })

  it('keeps cached rows and shows a Vietnamese warning on background failure', async () => {
    const params = { page: 0, size: 20 }
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } })
    queryClient.setQueryData(exerciseQueryKeys.list(params), createExercisePage())
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('private network detail')))

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/exercises']}>
          <ExerciseListPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.getByRole('link', { name: 'Barbell Squat' })).toBeInTheDocument()
    expect(await screen.findByText(/Không thể cập nhật dữ liệu mới/)).toBeInTheDocument()
    expect(screen.queryByText('private network detail')).not.toBeInTheDocument()
  })
})
