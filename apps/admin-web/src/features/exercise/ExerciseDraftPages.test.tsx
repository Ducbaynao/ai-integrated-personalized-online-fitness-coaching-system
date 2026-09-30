import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exerciseQueryKeys } from '../../services/exerciseApi.ts'
import { createExerciseDetail, createExerciseMetadata } from '../../test/exerciseTestData.ts'
import { ExerciseCreatePage, ExerciseEditPage } from './ExerciseDraftPages.tsx'

const EXERCISE_ID = '2c5f9430-c360-4b32-b70a-d6f92b76bfd4'

function renderDraftPage(path: string, queryClient = createQueryClient()) {
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/exercises/new" element={<ExerciseCreatePage />} />
          <Route path="/exercises/:exerciseId/edit" element={<ExerciseEditPage />} />
          <Route path="/exercises/:exerciseId" element={<h1>Chi tiết kiểm thử</h1>} />
          <Route path="/exercises" element={<h1>Danh sách kiểm thử</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { ...result, queryClient }
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  })
}

function successfulFetch(overrides: { detailStatus?: 'DRAFT' | 'ACTIVE' | 'ARCHIVED' } = {}) {
  return vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
    if (input.endsWith('/admin/exercises/metadata')) return Response.json(createExerciseMetadata())
    if (input.endsWith(`/admin/exercises/${EXERCISE_ID}`) && !init?.method) {
      return Response.json(createExerciseDetail({ status: overrides.detailStatus ?? 'DRAFT', version: 7 }))
    }
    if (input.endsWith(`/admin/exercises/${EXERCISE_ID}`) && init?.method === 'PUT') {
      return Response.json(createExerciseDetail({ status: 'DRAFT', version: 8, name: 'Updated Squat' }))
    }
    if (input.endsWith('/admin/exercises') && init?.method === 'POST') {
      return Response.json(createExerciseDetail({ status: 'DRAFT', version: 0 }), { status: 201 })
    }
    throw new Error(`Unexpected request: ${input}`)
  })
}

describe('exercise draft create and edit flows', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads only Admin metadata and creates a DRAFT with exact contract fields', async () => {
    const fetchMock = successfulFetch()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const queryClient = createQueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    renderDraftPage('/exercises/new', queryClient)

    await user.type(await screen.findByLabelText(/Mã bài tập/), 'NEW_SQUAT')
    await user.type(screen.getByLabelText(/Tên bài tập/), 'Barbell Squat')
    await user.selectOptions(screen.getByLabelText('Danh mục'), 'STRENGTH')
    await user.selectOptions(screen.getByLabelText('Kiểu vận động'), 'SQUAT')
    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    expect(await screen.findByRole('heading', { name: 'Chi tiết kiểm thử' })).toBeInTheDocument()
    const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    const payload = JSON.parse(postCall?.[1]?.body as string)
    expect(payload).toEqual({
      code: 'NEW_SQUAT',
      name: 'Barbell Squat',
      categoryCode: 'STRENGTH',
      description: null,
      instructions: null,
      difficulty: null,
      movementPattern: 'SQUAT',
      unilateral: false,
      tagCodes: [],
      variations: [],
    })
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/exercises/filter-metadata'))).toBe(false)
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: exerciseQueryKeys.lists() })
    expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toMatchObject({ status: 'DRAFT' })
  })

  it('prefills an English API name and sends the loaded expectedVersion on edit', async () => {
    const fetchMock = successfulFetch()
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`)

    const nameInput = await screen.findByLabelText(/Tên bài tập/)
    expect(nameInput).toHaveValue('Barbell Squat')
    await user.clear(nameInput)
    await user.type(nameInput, 'Updated Squat')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findByRole('heading', { name: 'Chi tiết kiểm thử' })).toBeInTheDocument()
    const putCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')
    const payload = JSON.parse(putCall?.[1]?.body as string)
    expect(payload.expectedVersion).toBe(7)
    expect(payload.exercise.name).toBe('Updated Squat')
  })

  it.each(['ACTIVE', 'ARCHIVED'] as const)('blocks direct editing of a %s exercise', async (status) => {
    vi.stubGlobal('fetch', successfulFetch({ detailStatus: status }))
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`)

    expect(await screen.findByRole('heading', { name: 'Bài tập này không thể chỉnh sửa' })).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(screen.getByText('Barbell Squat', { exact: false })).toBeInTheDocument()
  })

  it('rejects an invalid edit UUID without requesting detail', async () => {
    const fetchMock = successfulFetch()
    vi.stubGlobal('fetch', fetchMock)
    renderDraftPage('/exercises/not-a-uuid/edit')

    expect(await screen.findByRole('heading', { name: 'Đường dẫn bài tập không hợp lệ' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('not-a-uuid'))).toBe(false)
  })

  it('keeps input and maps a code conflict without showing the raw backend message', async () => {
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (init?.method === 'POST') return Response.json(
        { errorCode: 'EXERCISE_CODE_CONFLICT', message: 'duplicate key secret', fieldErrors: [] },
        { status: 409 },
      )
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDraftPage('/exercises/new')

    await user.type(await screen.findByLabelText(/Mã bài tập/), 'DUPLICATE')
    await user.type(screen.getByLabelText(/Tên bài tập/), 'Bench Press')
    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    expect(await screen.findAllByText('Mã bài tập đã được sử dụng. Vui lòng chọn mã khác.')).toHaveLength(2)
    expect(screen.getByLabelText(/Mã bài tập/)).toHaveValue('DUPLICATE')
    expect(screen.queryByText('duplicate key secret')).not.toBeInTheDocument()
  })

  it.each([
    ['network', () => Promise.reject(new TypeError('raw network failure')), 'Không thể kết nối đến hệ thống'],
    ['server', () => Promise.resolve(Response.json(
      { errorCode: 'INTERNAL_ERROR', message: 'raw server failure', fieldErrors: [] },
      { status: 503 },
    )), 'Hệ thống đang gặp sự cố'],
    ['generic', () => Promise.resolve(Response.json(
      { errorCode: 'UNKNOWN_FAILURE', message: 'raw generic failure', fieldErrors: [] },
      { status: 418 },
    )), 'Không thể lưu bài tập'],
  ])('keeps local input after a %s mutation error with safe copy', async (_kind, failure, expectedCopy) => {
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (init?.method === 'POST') return failure()
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDraftPage('/exercises/new')

    await user.type(await screen.findByLabelText(/Mã bài tập/), 'LOCAL_DRAFT')
    await user.type(screen.getByLabelText(/Tên bài tập/), 'Local Draft')
    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    expect(await screen.findByText(new RegExp(expectedCopy))).toBeInTheDocument()
    expect(screen.getByLabelText(/Tên bài tập/)).toHaveValue('Local Draft')
    expect(screen.queryByText(/raw (network|server|generic) failure/)).not.toBeInTheDocument()
  })

  it('prevents duplicate create submissions while the first request is pending', async () => {
    let resolveCreate: ((response: Response) => void) | undefined
    const pendingCreate = new Promise<Response>((resolve) => { resolveCreate = resolve })
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (init?.method === 'POST') return pendingCreate
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDraftPage('/exercises/new')

    await user.type(await screen.findByLabelText(/Mã bài tập/), 'ONE_REQUEST')
    await user.type(screen.getByLabelText(/Tên bài tập/), 'One Request')
    const submit = screen.getByRole('button', { name: 'Tạo bản nháp' })
    await user.dblClick(submit)

    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Đang lưu…' })).toBeDisabled()
    resolveCreate?.(Response.json(createExerciseDetail({ status: 'DRAFT' }), { status: 201 }))
    expect(await screen.findByRole('heading', { name: 'Chi tiết kiểm thử' })).toBeInTheDocument()
  })

  it('keeps local input and never auto-retries a version conflict', async () => {
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (input.endsWith(EXERCISE_ID) && !init?.method) return Response.json(createExerciseDetail({ status: 'DRAFT', version: 4 }))
      if (init?.method === 'PUT') return Response.json(
        { errorCode: 'EXERCISE_VERSION_CONFLICT', message: 'server version is secret', fieldErrors: [] },
        { status: 409 },
      )
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`)

    const nameInput = await screen.findByLabelText(/Tên bài tập/)
    await user.clear(nameInput)
    await user.type(nameInput, 'My local edit')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findByRole('heading', { name: 'Có phiên bản mới hơn trên hệ thống' })).toBeInTheDocument()
    expect(nameInput).toHaveValue('My local edit')
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1)
    expect(screen.queryByText('server version is secret')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tải bản mới nhất' }))
    expect(screen.getByRole('alertdialog', { name: 'Tải bản mới nhất?' })).toBeInTheDocument()
  })

  it('removes the editable form after a lifecycle conflict', async () => {
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (!init?.method) return Response.json(createExerciseDetail({ status: 'DRAFT' }))
      return Response.json({ errorCode: 'EXERCISE_LIFECYCLE_CONFLICT', message: 'raw', fieldErrors: [] }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`)

    await screen.findByRole('form')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findByRole('heading', { name: 'Bài tập không còn có thể chỉnh sửa' })).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  it('handles empty metadata and terminal permission errors with safe Vietnamese states', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json(createExerciseMetadata({
      categories: [], muscleGroups: [], equipment: [], tags: [], movementPatterns: [],
    })))
    vi.stubGlobal('fetch', fetchMock)
    const { unmount } = renderDraftPage('/exercises/new')
    expect(await screen.findByText(/Chưa có lựa chọn khả dụng cho/)).toBeInTheDocument()
    unmount()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(
      { errorCode: 'CATALOG_MANAGE_REQUIRED', message: 'raw permission detail', fieldErrors: [] },
      { status: 403 },
    )))
    renderDraftPage('/exercises/new')
    expect(await screen.findByRole('heading', { name: 'Bạn không có quyền truy cập' })).toBeInTheDocument()
    expect(screen.queryByText('raw permission detail')).not.toBeInTheDocument()
  })

  it('removes the editable form when the requested draft no longer exists', async () => {
    const fetchMock = vi.fn().mockImplementation(async (input: string) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (input.endsWith(EXERCISE_ID)) return Response.json(
        { errorCode: 'ADMIN_EXERCISE_NOT_FOUND', message: 'raw missing exercise detail', fieldErrors: [] },
        { status: 404 },
      )
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`)

    expect(await screen.findByRole('heading', { name: 'Không tìm thấy bài tập' })).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(screen.queryByText('raw missing exercise detail')).not.toBeInTheDocument()
  })

  it('shows a terminal session state when loading form metadata returns 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(
      { errorCode: 'SESSION_EXPIRED', message: 'raw session detail', fieldErrors: [] },
      { status: 401 },
    )))
    renderDraftPage('/exercises/new')

    expect(await screen.findByRole('heading', { name: 'Phiên đăng nhập đã hết hạn' })).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(screen.queryByText('raw session detail')).not.toBeInTheDocument()
  })

  it('warns on dirty cancel, supports Escape, and does not warn while pristine', async () => {
    vi.stubGlobal('fetch', successfulFetch())
    const user = userEvent.setup()
    renderDraftPage('/exercises/new')

    await screen.findByRole('form')
    await user.click(screen.getByRole('button', { name: 'Hủy' }))
    expect(await screen.findByRole('heading', { name: 'Danh sách kiểm thử' })).toBeInTheDocument()

    const { unmount } = renderDraftPage('/exercises/new')
    await user.type(await screen.findByLabelText(/Tên bài tập/), 'Unsaved name')
    await user.click(screen.getByRole('button', { name: 'Hủy' }))
    const dialog = screen.getByRole('alertdialog', { name: 'Bạn có thay đổi chưa lưu' })
    expect(dialog).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tiếp tục chỉnh sửa' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText(/Tên bài tập/)).toHaveValue('Unsaved name')
    unmount()
  })

  it('registers refresh protection only after the form becomes dirty', async () => {
    vi.stubGlobal('fetch', successfulFetch())
    const user = userEvent.setup()
    renderDraftPage('/exercises/new')
    await screen.findByRole('form')

    const pristineEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(pristineEvent)
    expect(pristineEvent.defaultPrevented).toBe(false)

    await user.type(screen.getByLabelText(/Tên bài tập/), 'Dirty')
    const dirtyEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirtyEvent)
    expect(dirtyEvent.defaultPrevented).toBe(true)
  })
})
