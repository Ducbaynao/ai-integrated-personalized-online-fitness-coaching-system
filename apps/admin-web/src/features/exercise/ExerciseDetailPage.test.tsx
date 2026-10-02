import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exerciseQueryKeys } from '../../services/exerciseApi.ts'
import { createExerciseDetail } from '../../test/exerciseTestData.ts'
import { ExerciseDetailPage } from './ExerciseDetailPage.tsx'

const EXERCISE_ID = '2c5f9430-c360-4b32-b70a-d6f92b76bfd4'
const NEXT_EXERCISE_ID = 'cf2961f5-8392-4d55-b48a-257c48a99ca7'

function renderDetail(
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 } } }),
  initialEntry: string | { pathname: string; state?: unknown } = `/exercises/${EXERCISE_ID}`,
  nextExerciseId?: string,
) {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/exercises/:exerciseId" element={(
            <>
              <ExerciseDetailPage />
              {nextExerciseId ? <Link to={`/exercises/${nextExerciseId}`}>Bài tập tiếp theo</Link> : null}
            </>
          )} />
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

  it('shows edit only for a DRAFT detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExerciseDetail({ status: 'DRAFT' }))))
    const { unmount } = render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={[`/exercises/${EXERCISE_ID}`]}>
          <Routes><Route path="/exercises/:exerciseId" element={<ExerciseDetailPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('link', { name: 'Chỉnh sửa' })).toHaveAttribute(
      'href',
      `/exercises/${EXERCISE_ID}/edit`,
    )
    unmount()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(createExerciseDetail({ status: 'ACTIVE' }))))
    renderDetail()
    await screen.findByRole('heading', { name: 'Barbell Squat' })
    expect(screen.queryByRole('link', { name: 'Chỉnh sửa' })).not.toBeInTheDocument()
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

  it('activates the displayed DRAFT snapshot, replaces detail cache, and invalidates lists', async () => {
    const draft = createExerciseDetail({ status: 'DRAFT', version: 5 })
    const active = createExerciseDetail({ status: 'ACTIVE', version: 6 })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(draft))
      .mockResolvedValueOnce(Response.json(active))
    vi.stubGlobal('fetch', fetchMock)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const user = userEvent.setup()
    renderDetail(queryClient)

    await user.click(await screen.findByRole('button', { name: 'Kích hoạt' }))
    const dialog = screen.getByRole('alertdialog', { name: 'Kích hoạt bài tập?' })
    expect(screen.getByRole('button', { name: 'Hủy' })).toHaveFocus()
    expect(dialog).toHaveAccessibleDescription(/Barbell Squat/)
    await user.click(screen.getByRole('button', { name: 'Kích hoạt bài tập' }))

    await screen.findByText('Đã kích hoạt Barbell Squat.')
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/v1/admin/exercises/${EXERCISE_ID}/activate`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ expectedVersion: 5 }),
    }))
    expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toEqual(active)
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: exerciseQueryKeys.lists() })
    expect(screen.queryByRole('button', { name: 'Kích hoạt' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Lưu trữ' })).toBeInTheDocument()
  })

  it('requires a trimmed archive reason and sends only the displayed ACTIVE version', async () => {
    const active = createExerciseDetail({ status: 'ACTIVE', version: 9 })
    const archived = createExerciseDetail({ status: 'ARCHIVED', version: 10 })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(active))
      .mockResolvedValueOnce(Response.json(archived))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Lưu trữ' }))
    const reason = screen.getByRole('textbox', { name: 'Lý do lưu trữ' })
    const confirm = screen.getByRole('button', { name: 'Lưu trữ bài tập' })
    expect(reason).toHaveAttribute('aria-required', 'true')
    expect(reason).toHaveAttribute('maxLength', '1000')
    expect(confirm).toBeDisabled()
    await user.type(reason, '   ')
    expect(confirm).toBeDisabled()
    await user.type(reason, 'Nội dung cũ   ')
    expect(confirm).toBeEnabled()
    await user.click(confirm)

    await screen.findByText('Đã lưu trữ Barbell Squat.')
    expect(fetchMock).toHaveBeenNthCalledWith(2, `/api/v1/admin/exercises/${EXERCISE_ID}/archive`, expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ expectedVersion: 9, reason: 'Nội dung cũ' }),
    }))
  })

  it('prevents duplicate lifecycle submissions while the first request is pending', async () => {
    let resolveMutation!: (response: Response) => void
    const pendingMutation = new Promise<Response>((resolve) => { resolveMutation = resolve })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(createExerciseDetail({ status: 'DRAFT', version: 3 })))
      .mockReturnValueOnce(pendingMutation)
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Kích hoạt' }))
    await user.dblClick(screen.getByRole('button', { name: 'Kích hoạt bài tập' }))
    expect(screen.getByRole('button', { name: 'Đang xử lý…' })).toBeDisabled()
    expect(screen.getByRole('alertdialog')).toHaveFocus()
    expect(fetchMock).toHaveBeenCalledTimes(2)

    resolveMutation(Response.json(createExerciseDetail({ status: 'ACTIVE', version: 4 })))
    await screen.findByText('Đã kích hoạt Barbell Squat.')
  })

  it.each([
    ['EXERCISE_VERSION_CONFLICT', 409, 'Bài tập đã được cập nhật', true],
    ['EXERCISE_LIFECYCLE_CONFLICT', 409, 'Trạng thái bài tập đã thay đổi', true],
    ['EXERCISE_CANONICAL_CONFLICT', 409, 'Chưa thể lưu trữ bài tập', false],
    ['VALIDATION_FAILED', 400, 'Bài tập chưa đủ điều kiện kích hoạt', false],
  ])('maps %s without raw messages or automatic mutation retries', async (errorCode, status, title, refreshRecommended) => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(createExerciseDetail({ status: 'DRAFT' })))
      .mockResolvedValueOnce(Response.json({ errorCode, message: 'raw private backend detail' }, { status }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Kích hoạt' }))
    await user.click(screen.getByRole('button', { name: 'Kích hoạt bài tập' }))

    expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument()
    expect(screen.queryByText('raw private backend detail')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tải lại chi tiết' }) !== null).toBe(refreshRecommended)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('keeps the snapshot visible after a network mutation error without auto retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(createExerciseDetail({ status: 'DRAFT' })))
      .mockRejectedValueOnce(new TypeError('raw offline detail'))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: 'Kích hoạt' }))
    await user.click(screen.getByRole('button', { name: 'Kích hoạt bài tập' }))

    expect(await screen.findByRole('heading', { name: 'Không thể kết nối đến hệ thống' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Barbell Squat' })).toBeInTheDocument()
    expect(screen.queryByText('raw offline detail')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('hides and removes cached detail when lifecycle authority is revoked', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(createExerciseDetail({ status: 'DRAFT' })))
      .mockResolvedValueOnce(Response.json({
        errorCode: 'CATALOG_MANAGE_REQUIRED',
        message: 'raw access detail',
      }, { status: 403 }))
    vi.stubGlobal('fetch', fetchMock)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
    const user = userEvent.setup()
    renderDetail(queryClient)

    await user.click(await screen.findByRole('button', { name: 'Kích hoạt' }))
    await user.click(screen.getByRole('button', { name: 'Kích hoạt bài tập' }))

    expect(await screen.findByRole('heading', { name: 'Bạn không có quyền thực hiện thao tác' })).toBeInTheDocument()
    expect(screen.queryByText('Barbell Squat')).not.toBeInTheDocument()
    expect(screen.queryByText('raw access detail')).not.toBeInTheDocument()
    await waitFor(() => expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toBeUndefined())
  })

  it.each([
    [403, 'CATALOG_MANAGE_REQUIRED', 'Bạn không có quyền thực hiện thao tác'],
    [404, 'ADMIN_EXERCISE_NOT_FOUND', 'Không tìm thấy bài tập'],
  ])('loads a new exercise after a terminal HTTP %s lifecycle failure', async (status, errorCode, terminalTitle) => {
    const nextExercise = createExerciseDetail({ id: NEXT_EXERCISE_ID, name: 'Romanian Deadlift', status: 'ACTIVE' })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json(createExerciseDetail({ status: 'DRAFT' })))
      .mockResolvedValueOnce(Response.json({ errorCode, message: 'raw terminal detail' }, { status }))
      .mockResolvedValueOnce(Response.json(nextExercise))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDetail(undefined, `/exercises/${EXERCISE_ID}`, NEXT_EXERCISE_ID)

    await user.click(await screen.findByRole('button', { name: 'Kích hoạt' }))
    await user.click(screen.getByRole('button', { name: 'Kích hoạt bài tập' }))
    expect(await screen.findByRole('heading', { name: terminalTitle })).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Bài tập tiếp theo' }))

    expect(await screen.findByRole('heading', { name: 'Romanian Deadlift' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: terminalTitle })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenNthCalledWith(3, `/api/v1/admin/exercises/${NEXT_EXERCISE_ID}`, expect.anything())
  })
})
