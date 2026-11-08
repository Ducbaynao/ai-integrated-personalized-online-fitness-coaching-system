import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { trainerApplicationApi } from '../../services/trainerApplicationApi.ts'
import type { TrainerVerificationStatus } from '../../types/trainerApplication.ts'

const PAGE_SIZE = 15

export function TrainerApplicationListPage() {
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [page, setPage] = useState(0)

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin-trainer-applications', page, statusFilter],
    queryFn: () =>
      trainerApplicationApi.getApplications({
        page,
        size: PAGE_SIZE,
        status: statusFilter || undefined,
      }),
    retry: false,
  })

  const getStatusBadgeClass = (status: TrainerVerificationStatus) => {
    switch (status) {
      case 'VERIFIED':
        return 'badge-success'
      case 'PENDING':
        return 'badge-warning'
      case 'REJECTED':
        return 'badge-danger'
      case 'SUSPENDED':
        return 'badge-danger'
      default:
        return 'badge-neutral'
    }
  }

  const getStatusLabel = (status: TrainerVerificationStatus) => {
    switch (status) {
      case 'VERIFIED':
        return 'Đã phê duyệt'
      case 'PENDING':
        return 'Chờ xét duyệt'
      case 'REJECTED':
        return 'Đã từ chối'
      case 'SUSPENDED':
        return 'Đình chỉ'
      default:
        return status
    }
  }

  return (
    <div className="page-container">
      <div className="page-header">
        <div>
          <h1 className="page-title">Hồ sơ xác minh Huấn luyện viên</h1>
          <p className="page-subtitle">
            Xét duyệt, kiểm tra bằng cấp và quyết định cấp quyền huấn luyện trên nền tảng.
          </p>
        </div>
      </div>

      {/* Filter Toolbar */}
      <div className="toolbar" role="toolbar" aria-label="Bộ lọc hồ sơ HLV">
        <div className="toolbar-group">
          <label htmlFor="status-filter" className="field-label">
            Trạng thái:
          </label>
          <select
            id="status-filter"
            aria-label="Lọc theo trạng thái"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value)
              setPage(0)
            }}
            className="select-input"
          >
            <option value="">Tất cả trạng thái</option>
            <option value="PENDING">Chờ xét duyệt (PENDING)</option>
            <option value="VERIFIED">Đã phê duyệt (VERIFIED)</option>
            <option value="REJECTED">Đã từ chối (REJECTED)</option>
            <option value="SUSPENDED">Đình chỉ (SUSPENDED)</option>
          </select>
        </div>
      </div>

      {/* Content States */}
      {isLoading ? (
        <StatePanel title="Đang tải danh sách hồ sơ…" />
      ) : isError ? (
        <StatePanel
          title="Không thể tải danh sách hồ sơ"
          description={error instanceof Error ? error.message : 'Lỗi kết nối tới máy chủ.'}
          tone="danger"
          action={
            <button className="button-primary" onClick={() => void refetch()}>
              Thử lại
            </button>
          }
        />
      ) : !data || data.items.length === 0 ? (
        <StatePanel
          title="Không tìm thấy hồ sơ nào"
          description={
            statusFilter
              ? 'Không có hồ sơ nào phù hợp với bộ lọc hiện tại.'
              : 'Hiện chưa có hồ sơ đăng ký xác minh nào.'
          }
        />
      ) : (
        <>
          <div className="table-responsive">
            <table className="data-table" aria-label="Danh sách hồ sơ HLV">
              <thead>
                <tr>
                  <th scope="col">Mã hồ sơ</th>
                  <th scope="col">Mã HLV</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">Ngày nộp</th>
                  <th scope="col">Ngày xét duyệt</th>
                  <th scope="col">Lý do từ chối</th>
                  <th scope="col">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((app) => (
                  <tr key={app.id}>
                    <td>
                      <code className="code-inline">{app.id.slice(0, 8)}…</code>
                    </td>
                    <td>
                      <code className="code-inline">{app.trainerId.slice(0, 8)}…</code>
                    </td>
                    <td>
                      <span className={`badge ${getStatusBadgeClass(app.status)}`}>
                        {getStatusLabel(app.status)}
                      </span>
                    </td>
                    <td>{app.submittedAt ? new Date(app.submittedAt).toLocaleString('vi-VN') : '—'}</td>
                    <td>{app.reviewedAt ? new Date(app.reviewedAt).toLocaleString('vi-VN') : '—'}</td>
                    <td>
                      {app.rejectionReason ? (
                        <span className="text-danger" title={app.rejectionReason}>
                          {app.rejectionReason.length > 40
                            ? `${app.rejectionReason.slice(0, 40)}…`
                            : app.rejectionReason}
                        </span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>
                      <Link
                        to={`/trainer-applications/${app.id}`}
                        className="button-link"
                        aria-label={`Xem chi tiết hồ sơ ${app.id}`}
                      >
                        Chi tiết
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination Controls */}
          {data.totalPages > 1 && (
            <div className="pagination" role="navigation" aria-label="Phân trang">
              <button
                className="button-secondary"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Trang trước
              </button>
              <span className="pagination-info">
                Trang {page + 1} / {data.totalPages} (Tổng {data.totalItems} hồ sơ)
              </span>
              <button
                className="button-secondary"
                disabled={page >= data.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                Trang sau
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
