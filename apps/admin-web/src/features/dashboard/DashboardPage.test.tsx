import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DashboardPage } from './DashboardPage.tsx'
import { userAdminApi } from '../../services/userAdminApi.ts'

vi.mock('../../services/userAdminApi.ts', () => ({
  userAdminApi: {
    getDashboardSummary: vi.fn(),
  },
}))

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders loading state initially', () => {
    vi.mocked(userAdminApi.getDashboardSummary).mockReturnValue(new Promise(() => {}))

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    )

    expect(screen.getByText('Đang tải dữ liệu tổng quan…')).toBeInTheDocument()
  })

  it('renders dashboard metrics and action required section when actions are pending', async () => {
    vi.mocked(userAdminApi.getDashboardSummary).mockResolvedValueOnce({
      pendingTrainerApplications: 4,
      totalUsers: 150,
      activeUsers: 130,
      suspendedUsers: 3,
      disabledUsers: 5,
      pendingVerificationUsers: 12,
    })

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('Công việc cần xử lý (Action Required)')).toBeInTheDocument()
    })

    expect(screen.getByText('Hồ sơ HLV chờ duyệt')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('Tài khoản đang tạm đình chỉ')).toBeInTheDocument()
    expect(screen.getAllByText('3')).toHaveLength(2)
    expect(screen.getByText('150')).toBeInTheDocument()
    expect(screen.getByText('130')).toBeInTheDocument()
  })

  it('renders error panel when api call fails', async () => {
    vi.mocked(userAdminApi.getDashboardSummary).mockRejectedValueOnce(new Error('Network error'))

    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    )

    await waitFor(() => {
      expect(screen.getByText('Không thể tải bảng điều khiển')).toBeInTheDocument()
    })
    expect(screen.getByText('Network error')).toBeInTheDocument()
  })
})
