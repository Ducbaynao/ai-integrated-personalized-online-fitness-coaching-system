import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { exerciseQueryKeys } from '../../services/exerciseApi.ts'
import { createExerciseDetail, createExerciseMetadata } from '../../test/exerciseTestData.ts'
import { ExerciseDetailPage } from './ExerciseDetailPage.tsx'
import { ExerciseCreatePage, ExerciseEditPage } from './ExerciseDraftPages.tsx'

const EXERCISE_ID = '2c5f9430-c360-4b32-b70a-d6f92b76bfd4'

function renderDraftPage(
  path: string,
  queryClient = createQueryClient(),
  options: {
    initialEntries?: string[]
    initialIndex?: number
    detailElement?: ReactNode
  } = {},
) {
  const router = createMemoryRouter([
    { path: '/exercises/new', element: <ExerciseCreatePage /> },
    { path: '/exercises/:exerciseId/edit', element: <ExerciseEditPage /> },
    { path: '/exercises/:exerciseId', element: options.detailElement ?? <h1>Chi tiết kiểm thử</h1> },
    { path: '/exercises', element: <h1>Danh sách kiểm thử</h1> },
  ], {
    initialEntries: options.initialEntries ?? [path],
    initialIndex: options.initialIndex,
  })
  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...result, queryClient, router }
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
    const queryClient = createQueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`, queryClient)

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
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: exerciseQueryKeys.lists() })
    expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toMatchObject({
      name: 'Updated Squat',
      version: 8,
    })
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

  it.each([
    ['permission revoked', 'CATALOG_MANAGE_REQUIRED', 403, 'Bạn không có quyền chỉnh sửa'],
    ['account unavailable', 'ACCOUNT_UNAVAILABLE', 403, 'Tài khoản không khả dụng'],
    ['not found', 'ADMIN_EXERCISE_NOT_FOUND', 404, 'Không tìm thấy bài tập'],
  ])('removes cached detail and unsafe actions after a terminal %s mutation', async (
    _scenario,
    errorCode,
    status,
    title,
  ) => {
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (!init?.method) return Response.json(createExerciseDetail({ status: 'DRAFT', version: 7 }))
      return Response.json({ errorCode, message: 'raw terminal detail', fieldErrors: [] }, { status })
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const { queryClient } = renderDraftPage(`/exercises/${EXERCISE_ID}/edit`)

    await screen.findByRole('form')
    expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toBeDefined()
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /chi tiết/i })).not.toBeInTheDocument()
    expect(screen.queryByText('Barbell Squat')).not.toBeInTheDocument()
    expect(screen.queryByText('raw terminal detail')).not.toBeInTheDocument()
    expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toBeUndefined()

    await user.click(screen.getByRole('button', { name: 'Quay lại danh sách' }))
    expect(await screen.findByRole('heading', { name: 'Danh sách kiểm thử' })).toBeInTheDocument()
  })

  it('clears stale detail after a lifecycle conflict and only opens freshly loaded detail', async () => {
    let detailRequests = 0
    const fetchMock = vi.fn().mockImplementation(async (input: string, init?: RequestInit) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (!init?.method) {
        detailRequests += 1
        return Response.json(createExerciseDetail(detailRequests === 1
          ? { status: 'DRAFT', version: 7, name: 'Stale Draft Name' }
          : { status: 'ACTIVE', version: 8, name: 'Fresh Active Name' }))
      }
      return Response.json({ errorCode: 'EXERCISE_LIFECYCLE_CONFLICT', message: 'raw', fieldErrors: [] }, { status: 409 })
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const { queryClient } = renderDraftPage(`/exercises/${EXERCISE_ID}/edit`, createQueryClient(), {
      detailElement: <ExerciseDetailPage />,
    })

    await screen.findByRole('form')
    await user.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findByRole('heading', { name: 'Bài tập không còn có thể chỉnh sửa' })).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
    expect(screen.queryByText('Stale Draft Name')).not.toBeInTheDocument()
    expect(queryClient.getQueryData(exerciseQueryKeys.detail(EXERCISE_ID))).toBeUndefined()

    await user.click(screen.getByRole('button', { name: 'Tải chi tiết mới nhất' }))
    expect(await screen.findByRole('heading', { name: 'Fresh Active Name' })).toBeInTheDocument()
    expect(screen.getByText('8')).toBeInTheDocument()
    expect(screen.queryByText('Stale Draft Name')).not.toBeInTheDocument()
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

  it('blocks browser Back and proceeds without changing the history destination', async () => {
    vi.stubGlobal('fetch', successfulFetch())
    const user = userEvent.setup()
    const { router } = renderDraftPage('/exercises/new', createQueryClient(), {
      initialEntries: ['/exercises', '/exercises/new'],
      initialIndex: 1,
    })

    await user.type(await screen.findByLabelText(/Tên bài tập/), 'Dirty back navigation')
    await act(async () => { await router.navigate(-1) })
    expect(screen.getByRole('alertdialog', { name: 'Bạn có thay đổi chưa lưu' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/exercises/new')

    await user.click(screen.getByRole('button', { name: 'Tiếp tục chỉnh sửa' }))
    expect(router.state.location.pathname).toBe('/exercises/new')
    expect(screen.getByLabelText(/Tên bài tập/)).toHaveValue('Dirty back navigation')

    await act(async () => { await router.navigate(-1) })
    expect(screen.getAllByRole('alertdialog')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Rời trang' }))
    expect(await screen.findByRole('heading', { name: 'Danh sách kiểm thử' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/exercises')
  })

  it('blocks browser Forward while dirty and keeps the form when staying', async () => {
    vi.stubGlobal('fetch', successfulFetch())
    const user = userEvent.setup()
    const { router } = renderDraftPage('/exercises/new', createQueryClient(), {
      initialEntries: ['/exercises/new', '/exercises'],
      initialIndex: 0,
    })

    await user.type(await screen.findByLabelText(/Tên bài tập/), 'Dirty forward navigation')
    await act(async () => { await router.navigate(1) })
    expect(screen.getByRole('alertdialog', { name: 'Bạn có thay đổi chưa lưu' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/exercises/new')

    await user.click(screen.getByRole('button', { name: 'Tiếp tục chỉnh sửa' }))
    expect(screen.getByLabelText(/Tên bài tập/)).toHaveValue('Dirty forward navigation')
    expect(router.state.location.pathname).toBe('/exercises/new')
  })

  it('keeps the editable snapshot stable when a background refetch finds a newer version', async () => {
    let detailRequests = 0
    const fetchMock = vi.fn().mockImplementation(async (input: string) => {
      if (input.endsWith('/metadata')) return Response.json(createExerciseMetadata())
      if (input.endsWith(EXERCISE_ID)) {
        detailRequests += 1
        return Response.json(createExerciseDetail(detailRequests === 1
          ? { status: 'DRAFT', version: 7, name: 'Loaded Snapshot' }
          : { status: 'DRAFT', version: 8, name: 'Latest Server Snapshot' }))
      }
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const queryClient = createQueryClient()
    renderDraftPage(`/exercises/${EXERCISE_ID}/edit`, queryClient)

    const nameInput = await screen.findByLabelText(/Tên bài tập/)
    await user.clear(nameInput)
    await user.type(nameInput, 'Local unsaved snapshot')
    act(() => {
      queryClient.setQueryData(
        exerciseQueryKeys.detail(EXERCISE_ID),
        createExerciseDetail({ status: 'DRAFT', version: 8, name: 'Latest Server Snapshot' }),
      )
    })

    expect(nameInput).toHaveValue('Local unsaved snapshot')
    expect(screen.getByText('Phiên bản 7')).toBeInTheDocument()
    expect(screen.queryByText('Phiên bản 8')).not.toBeInTheDocument()
    expect(await screen.findByText(/Máy chủ có phiên bản mới hơn/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Tải bản mới nhất' }))
    expect(screen.getByRole('alertdialog', { name: 'Tải bản mới nhất?' })).toBeInTheDocument()
    expect(nameInput).toHaveValue('Local unsaved snapshot')
    await user.click(screen.getByRole('button', { name: 'Tải và bỏ thay đổi' }))

    expect(await screen.findByDisplayValue('Latest Server Snapshot')).toBeInTheDocument()
    expect(screen.getByText('Phiên bản 8')).toBeInTheDocument()
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
