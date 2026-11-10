import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { UserListPage } from './UserListPage.tsx'
import { UserDetailPage } from './UserDetailPage.tsx'
import { userAdminApi } from '../../services/userAdminApi.ts'
import type { AdminUserDetailResponse, AdminUserPageResponse } from '../../types/userAdmin.ts'

vi.mock('../../services/userAdminApi.ts', () => ({
  userAdminApi: {
    getUsers: vi.fn(),
    getUserDetail: vi.fn(),
    suspendUser: vi.fn(),
    restoreUser: vi.fn(),
    disableUser: vi.fn(),
    forceLogoutUser: vi.fn(),
    changeUserRole: vi.fn(),
  },
}))

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  })
}

function renderListPage() {
  const queryClient = createTestQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <UserListPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('UserListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  const mockUsersPage: AdminUserPageResponse = {
    content: [
      {
        id: 'u-1',
        email: 'student1@fitcoach.internal',
        displayName: 'Nguyễn Văn Học Viên',
        phoneNumber: '0901234567',
        status: 'ACTIVE',
        roles: ['STUDENT'],
        hasStudentProfile: true,
        hasTrainerProfile: false,
        trainerVerificationStatus: null,
        createdAt: '2026-11-01T08:00:00Z',
        lastLoginAt: '2026-11-08T10:00:00Z',
      },
      {
        id: 'u-2',
        email: 'trainer1@fitcoach.internal',
        displayName: 'Trần Thị Huấn Luyện',
        phoneNumber: null,
        status: 'SUSPENDED',
        roles: ['TRAINER'],
        hasStudentProfile: false,
        hasTrainerProfile: true,
        trainerVerificationStatus: 'VERIFIED',
        createdAt: '2026-10-15T08:00:00Z',
        lastLoginAt: null,
      },
    ],
    page: 0,
    size: 20,
    totalElements: 2,
    totalPages: 1,
  }

  it('renders user list and filters', async () => {
    vi.mocked(userAdminApi.getUsers).mockResolvedValueOnce(mockUsersPage)

    renderListPage()

    expect(screen.getByText('Đang tải danh sách người dùng…')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('student1@fitcoach.internal')).toBeInTheDocument()
      expect(screen.getByText('trainer1@fitcoach.internal')).toBeInTheDocument()
    })

    expect(screen.getByText('Nguyễn Văn Học Viên')).toBeInTheDocument()
    expect(screen.getByText('Trần Thị Huấn Luyện')).toBeInTheDocument()
    expect(screen.getAllByText('Hoạt động (ACTIVE)').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Tạm đình chỉ (SUSPENDED)').length).toBeGreaterThan(0)
  })

  it('filters by status and searches', async () => {
    vi.mocked(userAdminApi.getUsers).mockResolvedValue(mockUsersPage)

    const user = userEvent.setup()
    renderListPage()

    await waitFor(() => {
      expect(screen.getByText('student1@fitcoach.internal')).toBeInTheDocument()
    })

    const searchInput = screen.getByPlaceholderText('Tên, email hoặc số điện thoại…')
    await user.type(searchInput, 'Nguyễn')

    await waitFor(() => {
      expect(userAdminApi.getUsers).toHaveBeenLastCalledWith({
        search: 'Nguyễn',
        status: undefined,
        role: undefined,
        page: 0,
        size: 15,
      })
    })
  })

  it('displays empty state when no users are found', async () => {
    vi.mocked(userAdminApi.getUsers).mockResolvedValueOnce({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    })

    renderListPage()

    await waitFor(() => {
      expect(screen.getByText('Không tìm thấy người dùng phù hợp')).toBeInTheDocument()
    })
  })
})

describe('UserDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  const mockDetailActive: AdminUserDetailResponse = {
    id: 'u-1',
    email: 'student1@fitcoach.internal',
    displayName: 'Nguyễn Văn Học Viên',
    phoneNumber: '0901234567',
    status: 'ACTIVE',
    preferredLocale: 'vi',
    timezone: 'Asia/Ho_Chi_Minh',
    emailVerifiedAt: '2026-11-01T08:00:00Z',
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-01T08:00:00Z',
    lastLoginAt: '2026-11-08T10:00:00Z',
    roles: ['STUDENT'],
    permissions: ['WORKOUT_READ', 'MEASUREMENT_WRITE'],
    studentProfile: {
      dateOfBirth: '2000-01-01',
      gender: 'MALE',
      trainingExperienceLevel: 'BEGINNER',
      trainingExperienceMonths: 3,
      availableDaysPerWeek: 4,
      preferredSessionMinutes: 45,
      onboardingCompleted: true,
    },
    trainerProfile: null,
    statusHistory: [
      {
        id: 'h-1',
        fromStatus: 'PENDING_VERIFICATION',
        toStatus: 'ACTIVE',
        changedBy: null,
        changedByName: 'Hệ thống xác thực',
        reason: 'Email verified',
        changedAt: '2026-11-01T08:00:00Z',
      },
    ],
  }

  const mockDetailSuspended: AdminUserDetailResponse = {
    ...mockDetailActive,
    id: 'u-2',
    status: 'SUSPENDED',
  }

  function renderDetailPage(userId: string) {
    const queryClient = createTestQueryClient()
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/users/${userId}`]}>
          <Routes>
            <Route path="/users/:userId" element={<UserDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  it('renders user details, profile sections, and history', async () => {
    vi.mocked(userAdminApi.getUserDetail).mockResolvedValueOnce(mockDetailActive)

    renderDetailPage('u-1')

    expect(screen.getByText('Đang tải thông tin chi tiết người dùng…')).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getAllByText('student1@fitcoach.internal').length).toBeGreaterThan(0)
      expect(screen.getByText('Hồ sơ Học viên')).toBeInTheDocument()
      expect(screen.getByText('Lịch sử thay đổi trạng thái tài khoản (Status History)')).toBeInTheDocument()
    })

    expect(screen.getByText('WORKOUT_READ')).toBeInTheDocument()
    expect(screen.getByText('Email verified')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tạm đình chỉ tài khoản' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Vô hiệu hóa vĩnh viễn' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Buộc đăng xuất toàn bộ phiên' })).toBeInTheDocument()
  })

  it('executes force logout successfully', async () => {
    vi.mocked(userAdminApi.getUserDetail).mockResolvedValue(mockDetailActive)
    vi.mocked(userAdminApi.forceLogoutUser).mockResolvedValueOnce({
      revokedSessionCount: 2,
      message: 'Revoked 2 sessions',
    })

    const user = userEvent.setup()
    renderDetailPage('u-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Buộc đăng xuất toàn bộ phiên' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Buộc đăng xuất toàn bộ phiên' }))

    // Dialog appears
    expect(screen.getByRole('heading', { name: 'Buộc đăng xuất toàn bộ phiên' })).toBeInTheDocument()

    const reasonInput = screen.getByLabelText(/Lý do thực hiện/)
    await user.type(reasonInput, 'Phat hien dang nhap bat thuong')

    const passwordInput = screen.getByLabelText(/Mật khẩu quản trị viên/)
    await user.type(passwordInput, 'adminSecret123')

    const confirmBtn = screen.getByRole('button', { name: 'Thu hồi toàn bộ phiên' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(userAdminApi.forceLogoutUser).toHaveBeenCalledWith('u-1', {
        reason: 'Phat hien dang nhap bat thuong',
        stepUpPassword: 'adminSecret123',
      })
      expect(screen.getByText(/Đã thu hồi thành công 2 phiên làm việc/)).toBeInTheDocument()
    })
  })

  it('executes suspend user with step-up password', async () => {
    vi.mocked(userAdminApi.getUserDetail).mockResolvedValueOnce(mockDetailActive)
    vi.mocked(userAdminApi.suspendUser).mockResolvedValueOnce(mockDetailSuspended)
    vi.mocked(userAdminApi.getUserDetail).mockResolvedValueOnce(mockDetailSuspended)

    const user = userEvent.setup()
    renderDetailPage('u-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Tạm đình chỉ tài khoản' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Tạm đình chỉ tài khoản' }))

    expect(screen.getByText('Tạm đình chỉ tài khoản người dùng')).toBeInTheDocument()

    const reasonInput = screen.getByLabelText(/Lý do thực hiện/)
    await user.type(reasonInput, 'Vi pham quy dinh cong dong')

    const passwordInput = screen.getByLabelText(/Mật khẩu quản trị viên/)
    await user.type(passwordInput, 'adminSecret123')

    const confirmBtn = screen.getByRole('button', { name: 'Xác nhận tạm đình chỉ' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(userAdminApi.suspendUser).toHaveBeenCalledWith('u-1', {
        reason: 'Vi pham quy dinh cong dong',
        stepUpPassword: 'adminSecret123',
      })
    })
  })

  it('executes restore user for suspended account', async () => {
    vi.mocked(userAdminApi.getUserDetail).mockResolvedValueOnce(mockDetailSuspended)
    vi.mocked(userAdminApi.restoreUser).mockResolvedValueOnce(mockDetailActive)
    vi.mocked(userAdminApi.getUserDetail).mockResolvedValueOnce(mockDetailActive)

    const user = userEvent.setup()
    renderDetailPage('u-2')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Khôi phục tài khoản' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Khôi phục tài khoản' }))

    expect(screen.getByText('Khôi phục tài khoản người dùng')).toBeInTheDocument()

    const reasonInput = screen.getByLabelText(/Lý do thực hiện/)
    await user.type(reasonInput, 'Khieu nai duoc chap thuan')

    const passwordInput = screen.getByLabelText(/Mật khẩu quản trị viên/)
    await user.type(passwordInput, 'adminSecret123')

    const confirmBtn = screen.getByRole('button', { name: 'Xác nhận khôi phục' })
    await user.click(confirmBtn)

    await waitFor(() => {
      expect(userAdminApi.restoreUser).toHaveBeenCalledWith('u-2', {
        reason: 'Khieu nai duoc chap thuan',
        stepUpPassword: 'adminSecret123',
      })
    })
  })
})
