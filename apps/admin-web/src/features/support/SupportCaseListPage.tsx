import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { supportApi } from '../../services/supportModerationAuditApi.ts'
import type { SupportCaseSummary } from '../../types/supportModerationAudit.ts'

export function SupportCaseListPage() {
  const [cases, setCases] = useState<SupportCaseSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState('')
  const [priorityFilter, setPriorityFilter] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  // Create case modal
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [requesterId, setRequesterId] = useState('')
  const [subject, setSubject] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('MEDIUM')
  const [category, setCategory] = useState('ACCOUNT')
  const [submitting, setSubmitting] = useState(false)
  const [modalError, setModalError] = useState<string | null>(null)

  const loadData = async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await supportApi.getSupportCases({
        status: statusFilter || undefined,
        priority: priorityFilter || undefined,
        search: search.trim() || undefined,
        page,
        size: 15,
      })
      setCases(res.content)
      setTotalPages(res.totalPages)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadData()
  }, [statusFilter, priorityFilter, page])

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setPage(0)
    void loadData()
  }

  const handleCreateCase = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setModalError(null)
    try {
      await supportApi.createSupportCase({
        requesterId: requesterId.trim(),
        subject: subject.trim(),
        description: description.trim(),
        priority,
        category,
      })
      setShowCreateModal(false)
      setRequesterId('')
      setSubject('')
      setDescription('')
      void loadData()
    } catch (err) {
      setModalError(err instanceof Error ? err.message : String(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="support-case-list-page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2>Quản lý yêu cầu hỗ trợ (Support Cases)</h2>
          <p style={{ color: '#6b7280' }}>
            Theo dõi, xử lý và phân công các phiếu khiếu nại, hỗ trợ kỹ thuật và điều chỉnh tài khoản của người dùng.
          </p>
        </div>
        <button className="button-primary" onClick={() => setShowCreateModal(true)}>
          + Tạo phiếu hỗ trợ
        </button>
      </div>

      <form onSubmit={handleSearchSubmit} style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
        <input
          type="text"
          placeholder="Tìm theo mã phiếu, tiêu đề..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ minWidth: '220px' }}
        />
        <select
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value)
            setPage(0)
          }}
          aria-label="Lọc theo trạng thái"
        >
          <option value="">Tất cả trạng thái</option>
          <option value="OPEN">Mở (OPEN)</option>
          <option value="IN_PROGRESS">Đang xử lý (IN_PROGRESS)</option>
          <option value="RESOLVED">Đã giải quyết (RESOLVED)</option>
          <option value="CLOSED">Đã đóng (CLOSED)</option>
        </select>
        <select
          value={priorityFilter}
          onChange={(e) => {
            setPriorityFilter(e.target.value)
            setPage(0)
          }}
          aria-label="Lọc theo mức độ ưu tiên"
        >
          <option value="">Tất cả mức ưu tiên</option>
          <option value="LOW">Thấp (LOW)</option>
          <option value="MEDIUM">Trung bình (MEDIUM)</option>
          <option value="HIGH">Cao (HIGH)</option>
          <option value="CRITICAL">Khẩn cấp (CRITICAL)</option>
        </select>
        <button type="submit" className="button-secondary">
          Tìm kiếm
        </button>
      </form>

      {loading && <p>Đang tải danh sách phiếu hỗ trợ...</p>}
      {error && <div className="error-message" style={{ color: '#dc2626' }}>{error}</div>}

      {!loading && !error && cases.length === 0 && (
        <p>Không tìm thấy phiếu hỗ trợ nào phù hợp.</p>
      )}

      {!loading && !error && cases.length > 0 && (
        <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ textAlign: 'left', borderBottom: '2px solid #e5e7eb' }}>
              <th style={{ padding: '0.75rem' }}>Mã phiếu</th>
              <th style={{ padding: '0.75rem' }}>Tiêu đề</th>
              <th style={{ padding: '0.75rem' }}>Người yêu cầu</th>
              <th style={{ padding: '0.75rem' }}>Trạng thái</th>
              <th style={{ padding: '0.75rem' }}>Mức ưu tiên</th>
              <th style={{ padding: '0.75rem' }}>Người phụ trách</th>
              <th style={{ padding: '0.75rem' }}>Thời gian mở</th>
              <th style={{ padding: '0.75rem' }}>Chi tiết</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => (
              <tr key={c.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                <td style={{ padding: '0.75rem' }}>
                  <strong>{c.caseNumber}</strong>
                </td>
                <td style={{ padding: '0.75rem' }}>{c.subject}</td>
                <td style={{ padding: '0.75rem' }}>{c.requesterName}</td>
                <td style={{ padding: '0.75rem' }}>
                  <span
                    style={{
                      padding: '0.2rem 0.5rem',
                      borderRadius: '4px',
                      fontSize: '0.75rem',
                      fontWeight: 'bold',
                      backgroundColor:
                        c.status === 'OPEN'
                          ? '#fef3c7'
                          : c.status === 'IN_PROGRESS'
                            ? '#dbeafe'
                            : c.status === 'RESOLVED'
                              ? '#dcfce7'
                              : '#f3f4f6',
                      color:
                        c.status === 'OPEN'
                          ? '#92400e'
                          : c.status === 'IN_PROGRESS'
                            ? '#1e40af'
                            : c.status === 'RESOLVED'
                              ? '#15803d'
                              : '#4b5563',
                    }}
                  >
                    {c.status}
                  </span>
                </td>
                <td style={{ padding: '0.75rem' }}>
                  <span
                    style={{
                      color:
                        c.priority === 'CRITICAL' || c.priority === 'HIGH'
                          ? '#dc2626'
                          : '#4b5563',
                      fontWeight: c.priority === 'CRITICAL' ? 'bold' : 'normal',
                    }}
                  >
                    {c.priority}
                  </span>
                </td>
                <td style={{ padding: '0.75rem' }}>{c.assignedToAdminName ?? 'Chưa phân công'}</td>
                <td style={{ padding: '0.75rem', fontSize: '0.85rem' }}>
                  {new Date(c.openedAt).toLocaleString()}
                </td>
                <td style={{ padding: '0.75rem' }}>
                  <Link to={`/support-cases/${c.id}`} className="button-secondary" style={{ fontSize: '0.85rem' }}>
                    Xem & Xử lý
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {totalPages > 1 && (
        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>
            Trang trước
          </button>
          <span>Trang {page + 1} / {totalPages}</span>
          <button disabled={page >= totalPages - 1} onClick={() => setPage(page + 1)}>
            Trang sau
          </button>
        </div>
      )}

      {showCreateModal && (
        <div
          role="dialog"
          aria-modal="true"
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0,0,0,0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
          }}
        >
          <div
            style={{
              backgroundColor: '#fff',
              padding: '1.5rem',
              borderRadius: '8px',
              maxWidth: '500px',
              width: '100%',
            }}
          >
            <h3>Tạo phiếu hỗ trợ mới</h3>
            {modalError && <p style={{ color: '#dc2626' }}>{modalError}</p>}
            <form onSubmit={(e) => void handleCreateCase(e)}>
              <div style={{ marginBottom: '1rem' }}>
                <label>Requester User ID (UUID):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={requesterId}
                  onChange={(e) => setRequesterId(e.target.value)}
                  placeholder="UUID người yêu cầu hỗ trợ..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Tiêu đề:</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={subject}
                  onChange={(e) => setSubject(e.target.value)}
                  placeholder="Mô tả tóm tắt vấn đề..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Nội dung chi tiết:</label>
                <textarea
                  required
                  rows={4}
                  style={{ width: '100%' }}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Chi tiết yêu cầu..."
                />
              </div>

              <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem' }}>
                <div style={{ flex: 1 }}>
                  <label>Mức ưu tiên:</label>
                  <select
                    style={{ width: '100%' }}
                    value={priority}
                    onChange={(e) => setPriority(e.target.value)}
                  >
                    <option value="LOW">Thấp (LOW)</option>
                    <option value="MEDIUM">Trung bình (MEDIUM)</option>
                    <option value="HIGH">Cao (HIGH)</option>
                    <option value="CRITICAL">Khẩn cấp (CRITICAL)</option>
                  </select>
                </div>
                <div style={{ flex: 1 }}>
                  <label>Phân loại:</label>
                  <select
                    style={{ width: '100%' }}
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                  >
                    <option value="ACCOUNT">Tài khoản (ACCOUNT)</option>
                    <option value="DATA_CORRECTION">Hiệu chỉnh dữ liệu (DATA_CORRECTION)</option>
                    <option value="PAYMENT">Thanh toán (PAYMENT)</option>
                    <option value="TECHNICAL">Kỹ thuật (TECHNICAL)</option>
                  </select>
                </div>
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                <button
                  type="button"
                  className="button-secondary"
                  disabled={submitting}
                  onClick={() => setShowCreateModal(false)}
                >
                  Hủy
                </button>
                <button type="submit" className="button-primary" disabled={submitting}>
                  {submitting ? 'Đang tạo...' : 'Tạo phiếu'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
