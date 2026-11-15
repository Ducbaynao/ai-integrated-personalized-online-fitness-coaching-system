import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { userAdminApi } from '../../services/userAdminApi.ts'
import type { AccountStatus } from '../../types/userAdmin.ts'

const PAGE_SIZE = 15

export function UserListPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [roleFilter, setRoleFilter] = useState<string>('')
  const [page, setPage] = useState(0)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin-users', page, searchQuery, statusFilter, roleFilter],
    queryFn: () =>
      userAdminApi.getUsers({
        page,
        size: PAGE_SIZE,
        search: searchQuery.trim() || undefined,
        status: statusFilter || undefined,
        role: roleFilter || undefined,
      }),
    retry: false,
  })

  const getStatusBadgeClass = (status: AccountStatus) => {
    switch (status) {
      case 'ACTIVE':
        return 'badge-success'
      case 'SUSPENDED':
        return 'badge-warning'
      case 'DISABLED':
      case 'DELETED':
        return 'badge-danger'
      case 'PENDING_VERIFICATION':
      default:
        return 'badge-neutral'
    }
  }

  const getStatusLabel = (status: AccountStatus) => {
    switch (status) {
      case 'ACTIVE':
        return 'Hoạt động (ACTIVE)'
      case 'SUSPENDED':
        return 'Tạm đình chỉ (SUSPENDED)'
      case 'DISABLED':
        return 'Vô hiệu hóa (DISABLED)'
      case 'PENDING_VERIFICATION':
        return 'Chờ xác minh (PENDING)'
      case 'DELETION_PENDING':
        return 'Chờ xóa (DELETION_PENDING)'
      case 'DELETED':
        return 'Đã xóa (DELETED)'
      default:
        return status
    }
  }

  const handleResetFilters = () => {
    setSearchQuery('')
    setStatusFilter('')
    setRoleFilter('')
    setPage(0)
  }

  return (
    <div className="page-container">
      <div className="page-header">
        <div>
          <h1 className="page-title">Quản trị Người dùng & Vai trò</h1>
          <p className="page-subtitle">
            Danh bạ tài khoản, trạng thái vòng đời, cấp quyền và quản lý phiên đăng nhập (AD-02).
          </p>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="toolbar" role="toolbar" aria-label="Bộ lọc người dùng">
        <div className="toolbar-group">
          <label htmlFor="user-search" className="field-label">
            Tìm kiếm:
          </label>
          <input
            id="user-search"
            type="search"
            className="text-input"
            placeholder="Tên, email hoặc số điện thoại…"
            value={searchQuery}
            onChange={(e) => {
              setSearchQuery(e.target.value)
              setPage(0)
            }}
          />
        </div>

        <div className="toolbar-group">
          <label htmlFor="status-filter" className="field-label">
            Trạng thái:
          </label>
          <select
            id="status-filter"
            aria-label="Lọc theo trạng thái tài khoản"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value)
              setPage(0)
            }}
            className="select-input"
          >
            <option value="">Tất cả trạng thái</option>
            <option value="ACTIVE">Hoạt động (ACTIVE)</option>
            <option value="SUSPENDED">Tạm đình chỉ (SUSPENDED)</option>
            <option value="DISABLED">Vô hiệu hóa (DISABLED)</option>
            <option value="PENDING_VERIFICATION">Chờ xác minh (PENDING)</option>
          </select>
        </div>

        <div className="toolbar-group">
          <label htmlFor="role-filter" className="field-label">
            Vai trò:
          </label>
          <select
            id="role-filter"
            aria-label="Lọc theo vai trò"
            value={roleFilter}
            onChange={(e) => {
              setRoleFilter(e.target.value)
              setPage(0)
            }}
            className="select-input"
          >
            <option value="">Tất cả vai trò</option>
            <option value="STUDENT">Học viên (STUDENT)</option>
            <option value="TRAINER">Huấn luyện viên (TRAINER)</option>
            <option value="ADMIN">Quản trị viên (ADMIN)</option>
            <option value="CONTENT_ADMIN">Quản trị nội dung (CONTENT_ADMIN)</option>
          </select>
        </div>

        <div className="toolbar-group" style={{ alignSelf: 'flex-end' }}>
          <button
            type="button"
            className="button-secondary"
            onClick={handleResetFilters}
          >
            Đặt lại bộ lọc
          </button>
        </div>
      </div>

      {/* Content States */}
      {isLoading ? (
        <StatePanel title="Đang tải danh sách người dùng…" />
      ) : isError ? (
        <StatePanel
          title="Không thể tải danh sách người dùng"
          description={error instanceof Error ? error.message : 'Lỗi kết nối tới máy chủ.'}
          tone="danger"
          action={
            <button className="button-primary" onClick={() => void refetch()}>
              Thử lại
            </button>
          }
        />
      ) : !data || data.content.length === 0 ? (
        <StatePanel
          title="Không tìm thấy người dùng phù hợp"
          description="Thử thay đổi từ khóa tìm kiếm hoặc bỏ bớt các tiêu chí lọc."
          tone="neutral"
          action={
            <button className="button-secondary" onClick={handleResetFilters}>
              Xóa bộ lọc
            </button>
          }
        />
      ) : (
        <div className="table-responsive">
          <table className="data-table" aria-label="Bảng danh sách người dùng">
            <thead>
              <tr>
                <th scope="col">Người dùng</th>
                <th scope="col">Số điện thoại</th>
                <th scope="col">Vai trò</th>
                <th scope="col">Hồ sơ năng lực</th>
                <th scope="col">Trạng thái</th>
                <th scope="col">Lần đăng nhập cuối</th>
                <th scope="col">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((user) => (
                <tr key={user.id}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{user.displayName}</div>
                    <div className="text-muted" style={{ fontSize: '0.85rem' }}>
                      {user.email}
                    </div>
                  </td>
                  <td>{user.phoneNumber || '—'}</td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.25rem', flexWrap: 'wrap' }}>
                      {user.roles && user.roles.length > 0 ? (
                        user.roles.map((r) => (
                          <span key={r} className="badge badge-neutral">
                            {r}
                          </span>
                        ))
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </div>
                  </td>
                  <td>
                    <div style={{ fontSize: '0.85rem' }}>
                      {user.hasStudentProfile ? <div>✓ Học viên</div> : null}
                      {user.hasTrainerProfile ? (
                        <div>
                          ✓ HLV{' '}
                          {user.trainerVerificationStatus ? (
                            <span
                              className={`badge ${
                                user.trainerVerificationStatus === 'VERIFIED'
                                  ? 'badge-success'
                                  : user.trainerVerificationStatus === 'PENDING'
                                  ? 'badge-warning'
                                  : 'badge-danger'
                              }`}
                              style={{ fontSize: '0.75rem', padding: '0.1rem 0.35rem' }}
                            >
                              {user.trainerVerificationStatus}
                            </span>
                          ) : null}
                        </div>
                      ) : null}
                      {!user.hasStudentProfile && !user.hasTrainerProfile ? (
                        <span className="text-muted">Chưa tạo hồ sơ</span>
                      ) : null}
                    </div>
                  </td>
                  <td>
                    <span className={`badge ${getStatusBadgeClass(user.status)}`}>
                      {getStatusLabel(user.status)}
                    </span>
                  </td>
                  <td style={{ fontSize: '0.85rem' }}>
                    {user.lastLoginAt
                      ? new Date(user.lastLoginAt).toLocaleString('vi-VN')
                      : 'Chưa đăng nhập'}
                  </td>
                  <td>
                    <Link
                      to={`/users/${user.id}`}
                      className="button-link"
                      aria-label={`Chi tiết tài khoản ${user.displayName}`}
                    >
                      Chi tiết
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination Controls */}
          <div
            className="pagination-bar"
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginTop: '1rem',
              padding: '0.5rem 0',
            }}
          >
            <div className="text-muted" style={{ fontSize: '0.9rem' }}>
              Hiển thị {data.content.length} / tổng số {data.totalElements} tài khoản
              (Trang {data.page + 1} / {data.totalPages || 1})
            </div>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button
                type="button"
                className="button-secondary"
                disabled={data.page === 0}
                onClick={() => setPage((p) => Math.max(p - 1, 0))}
              >
                Trang trước
              </button>
              <button
                type="button"
                className="button-secondary"
                disabled={data.page >= data.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                Trang sau
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
