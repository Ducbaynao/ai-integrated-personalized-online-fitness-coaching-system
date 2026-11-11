import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { moderationApi } from '../../services/supportModerationAuditApi.ts'
import type { ModerationCaseSummary } from '../../types/supportModerationAudit.ts'

export function ModerationCaseListPage() {
  const [cases, setCases] = useState<ModerationCaseSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState('')
  const [priorityFilter, setPriorityFilter] = useState('')
  const [targetTypeFilter, setTargetTypeFilter] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  // Create case modal
  const [showCreateModal, setShowCreateModal] = useState(false)
  const [targetType, setTargetType] = useState('USER')
  const [targetId, setTargetId] = useState('')
  const [reason, setReason] = useState('')
  const [priority, setPriority] = useState('MEDIUM')
  const [notes, setNotes] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [modalError, setModalError] = useState<string | null>(null)

  const loadData = async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await moderationApi.getModerationCases({
        status: statusFilter || undefined,
        priority: priorityFilter || undefined,
        targetType: targetTypeFilter || undefined,
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
  }, [statusFilter, priorityFilter, targetTypeFilter, page])

  const handleCreateCase = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setModalError(null)
    try {
      await moderationApi.createModerationCase({
        targetType,
        targetId: targetId.trim(),
        reason: reason.trim(),
        priority,
        notes: notes.trim() || undefined,
      })
      setShowCreateModal(false)
      setTargetId('')
      setReason('')
      setNotes('')
      void loadData()
    } catch (err) {
      setModalError(err instanceof Error ? err.message : String(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="moderation-case-list-page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2>Quản lý kiểm duyệt nội dung & vi phạm (Moderation Cases)</h2>
          <p style={{ color: '#6b7280' }}>
            Xử lý báo cáo vi phạm, cảnh cáo, tạm dừng hoặc vô hiệu hóa tài khoản và gỡ bỏ nội dung không phù hợp.
          </p>
        </div>
        <button className="button-primary" onClick={() => setShowCreateModal(true)}>
          + Tạo case kiểm duyệt
        </button>
      </div>

      <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
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
          <option value="INVESTIGATING">Đang điều tra (INVESTIGATING)</option>
          <option value="ACTIONED">Đã xử lý (ACTIONED)</option>
          <option value="DISMISSED">Bỏ qua (DISMISSED)</option>
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
        <select
          value={targetTypeFilter}
          onChange={(e) => {
            setTargetTypeFilter(e.target.value)
            setPage(0)
          }}
          aria-label="Lọc theo loại đối tượng"
        >
          <option value="">Tất cả loại đối tượng</option>
          <option value="USER">Người dùng (USER)</option>
          <option value="POST">Bài đăng (POST)</option>
          <option value="COMMENT">Bình luận (COMMENT)</option>
        </select>
        <button className="button-secondary" onClick={() => void loadData()}>
          Làm mới
        </button>
      </div>

      {loading && <p>Đang tải danh sách case kiểm duyệt...</p>}
      {error && <div className="error-message" style={{ color: '#dc2626' }}>{error}</div>}

      {!loading && !error && cases.length === 0 && (
        <p>Không có case kiểm duyệt nào.</p>
      )}

      {!loading && !error && cases.length > 0 && (
        <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ textAlign: 'left', borderBottom: '2px solid #e5e7eb' }}>
              <th style={{ padding: '0.75rem' }}>Đối tượng</th>
              <th style={{ padding: '0.75rem' }}>Lý do / Vi phạm</th>
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
                  <strong>{c.targetType}</strong>
                  <div style={{ fontSize: '0.8rem', color: '#6b7280' }}>
                    {c.targetDisplayName ?? c.targetId}
                  </div>
                </td>
                <td style={{ padding: '0.75rem' }}>
                  {c.createdReason}
                  {c.notes && <div style={{ fontSize: '0.8rem', color: '#9ca3af' }}>{c.notes}</div>}
                </td>
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
                          : c.status === 'INVESTIGATING'
                            ? '#dbeafe'
                            : c.status === 'ACTIONED'
                              ? '#dcfce7'
                              : '#f3f4f6',
                      color:
                        c.status === 'OPEN'
                          ? '#92400e'
                          : c.status === 'INVESTIGATING'
                            ? '#1e40af'
                            : c.status === 'ACTIONED'
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
                  <Link to={`/moderation-cases/${c.id}`} className="button-secondary" style={{ fontSize: '0.85rem' }}>
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
            <h3>Tạo case kiểm duyệt mới</h3>
            {modalError && <p style={{ color: '#dc2626' }}>{modalError}</p>}
            <form onSubmit={(e) => void handleCreateCase(e)}>
              <div style={{ marginBottom: '1rem' }}>
                <label>Loại đối tượng vi phạm:</label>
                <select
                  style={{ width: '100%' }}
                  value={targetType}
                  onChange={(e) => setTargetType(e.target.value)}
                >
                  <option value="USER">Người dùng (USER)</option>
                  <option value="POST">Bài viết (POST)</option>
                  <option value="COMMENT">Bình luận (COMMENT)</option>
                </select>
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Target ID (UUID):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={targetId}
                  onChange={(e) => setTargetId(e.target.value)}
                  placeholder="UUID đối tượng vi phạm..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Lý do vi phạm:</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Nêu rõ nội dung vi phạm tiêu chuẩn..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Mức độ ưu tiên:</label>
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

              <div style={{ marginBottom: '1rem' }}>
                <label>Ghi chú ban đầu:</label>
                <textarea
                  rows={3}
                  style={{ width: '100%' }}
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Ghi chú thêm..."
                />
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
                  {submitting ? 'Đang tạo...' : 'Tạo case'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
