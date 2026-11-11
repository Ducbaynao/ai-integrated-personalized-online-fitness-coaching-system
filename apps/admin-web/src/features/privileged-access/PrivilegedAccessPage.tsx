import { useEffect, useState } from 'react'
import { privilegedAccessApi } from '../../services/supportModerationAuditApi.ts'
import type { PrivilegedAccess } from '../../types/supportModerationAudit.ts'

export function PrivilegedAccessPage() {
  const [requests, setRequests] = useState<PrivilegedAccess[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [statusFilter, setStatusFilter] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  // Create request modal state
  const [showModal, setShowModal] = useState(false)
  const [targetUserId, setTargetUserId] = useState('')
  const [purpose, setPurpose] = useState('DATA_CORRECTION')
  const [accessScope, setAccessScope] = useState('{"domains":["MEASUREMENT"]}')
  const [reason, setReason] = useState('')
  const [relatedSupportCaseId, setRelatedSupportCaseId] = useState('')
  const [durationMinutes, setDurationMinutes] = useState(60)
  const [stepUpPassword, setStepUpPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [modalError, setModalError] = useState<string | null>(null)

  const loadData = async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await privilegedAccessApi.getRequests({
        status: statusFilter || undefined,
        page,
        size: 15,
      })
      setRequests(res.content)
      setTotalPages(res.totalPages)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadData()
  }, [statusFilter, page])

  const handleCreateRequest = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setModalError(null)
    try {
      await privilegedAccessApi.requestAccess({
        targetUserId: targetUserId.trim(),
        purpose: purpose.trim(),
        accessScope: accessScope.trim(),
        reason: reason.trim(),
        relatedSupportCaseId: relatedSupportCaseId.trim() || undefined,
        durationMinutes: Number(durationMinutes) || 60,
        stepUpPassword: stepUpPassword.trim() || undefined,
      })
      setShowModal(false)
      setTargetUserId('')
      setReason('')
      setStepUpPassword('')
      void loadData()
    } catch (err) {
      setModalError(err instanceof Error ? err.message : String(err))
    } finally {
      setSubmitting(false)
    }
  }

  const handleRevoke = async (id: string) => {
    const revReason = prompt('Nhập lý do thu hồi quyền truy cập:')
    if (!revReason || !revReason.trim()) return
    try {
      await privilegedAccessApi.revokeAccess(id, { reason: revReason.trim() })
      void loadData()
    } catch (err) {
      alert('Thu hồi thất bại: ' + (err instanceof Error ? err.message : String(err)))
    }
  }

  return (
    <div className="privileged-access-page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2>Quản trị quyền truy cập đặc quyền (Privileged Data Access)</h2>
          <p style={{ color: '#6b7280' }}>
            Giám sát, yêu cầu và thu hồi các phiên truy cập dữ liệu nhạy cảm của người dùng có kiểm soát và giới hạn thời gian.
          </p>
        </div>
        <button className="button-primary" onClick={() => setShowModal(true)}>
          + Yêu cầu quyền truy cập
        </button>
      </div>

      <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem' }}>
        <select
          value={statusFilter}
          onChange={(e) => {
            setStatusFilter(e.target.value)
            setPage(0)
          }}
          aria-label="Lọc theo trạng thái"
        >
          <option value="">Tất cả trạng thái</option>
          <option value="ACTIVE">Đang hoạt động (ACTIVE)</option>
          <option value="EXPIRED">Đã hết hạn (EXPIRED)</option>
          <option value="REVOKED">Đã thu hồi (REVOKED)</option>
          <option value="REQUESTED">Đang chờ duyệt (REQUESTED)</option>
        </select>
        <button className="button-secondary" onClick={() => void loadData()}>
          Làm mới
        </button>
      </div>

      {loading && <p>Đang tải dữ liệu...</p>}
      {error && <div className="error-message" style={{ color: '#dc2626' }}>{error}</div>}

      {!loading && !error && requests.length === 0 && (
        <p>Không có yêu cầu truy cập đặc quyền nào.</p>
      )}

      {!loading && !error && requests.length > 0 && (
        <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ textAlign: 'left', borderBottom: '2px solid #e5e7eb' }}>
              <th style={{ padding: '0.75rem' }}>Mục đích</th>
              <th style={{ padding: '0.75rem' }}>Admin yêu cầu</th>
              <th style={{ padding: '0.75rem' }}>Đối tượng học viên</th>
              <th style={{ padding: '0.75rem' }}>Phiếu hỗ trợ</th>
              <th style={{ padding: '0.75rem' }}>Trạng thái</th>
              <th style={{ padding: '0.75rem' }}>Hạn dùng</th>
              <th style={{ padding: '0.75rem' }}>Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {requests.map((req) => (
              <tr key={req.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                <td style={{ padding: '0.75rem' }}>
                  <strong>{req.purpose}</strong>
                  <div style={{ fontSize: '0.8rem', color: '#6b7280' }}>{req.reason}</div>
                </td>
                <td style={{ padding: '0.75rem' }}>{req.requestedByAdminName ?? req.requestedByAdminId}</td>
                <td style={{ padding: '0.75rem' }}>{req.targetUserName ?? req.targetUserId}</td>
                <td style={{ padding: '0.75rem' }}>{req.supportCaseNumber ?? (req.relatedSupportCaseId ? req.relatedSupportCaseId.substring(0, 8) : '-')}</td>
                <td style={{ padding: '0.75rem' }}>
                  <span
                    style={{
                      padding: '0.2rem 0.5rem',
                      borderRadius: '4px',
                      fontSize: '0.75rem',
                      fontWeight: 'bold',
                      backgroundColor: req.currentlyActive ? '#dcfce7' : '#f3f4f6',
                      color: req.currentlyActive ? '#15803d' : '#4b5563',
                    }}
                  >
                    {req.status} {req.currentlyActive ? '●' : ''}
                  </span>
                </td>
                <td style={{ padding: '0.75rem', fontSize: '0.85rem' }}>
                  {req.expiresAt ? new Date(req.expiresAt).toLocaleString() : '-'}
                </td>
                <td style={{ padding: '0.75rem' }}>
                  {req.currentlyActive && (
                    <button
                      className="button-danger"
                      style={{ padding: '0.25rem 0.5rem', fontSize: '0.8rem' }}
                      onClick={() => void handleRevoke(req.id)}
                    >
                      Thu hồi
                    </button>
                  )}
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

      {showModal && (
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
            <h3>Yêu cầu quyền truy cập đặc quyền</h3>
            {modalError && <p style={{ color: '#dc2626' }}>{modalError}</p>}
            <form onSubmit={(e) => void handleCreateRequest(e)}>
              <div style={{ marginBottom: '1rem' }}>
                <label>Target User ID (UUID):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={targetUserId}
                  onChange={(e) => setTargetUserId(e.target.value)}
                  placeholder="UUID học viên cần truy cập..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Mục đích:</label>
                <select
                  style={{ width: '100%' }}
                  value={purpose}
                  onChange={(e) => setPurpose(e.target.value)}
                >
                  <option value="DATA_CORRECTION">Hiệu chỉnh dữ liệu (DATA_CORRECTION)</option>
                  <option value="SUPPORT_INVESTIGATION">Điều tra hỗ trợ (SUPPORT_INVESTIGATION)</option>
                  <option value="SECURITY_AUDIT">Kiểm tra bảo mật (SECURITY_AUDIT)</option>
                </select>
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Phạm vi (JSON):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={accessScope}
                  onChange={(e) => setAccessScope(e.target.value)}
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Lý do (bắt buộc):</label>
                <textarea
                  required
                  rows={3}
                  style={{ width: '100%' }}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Nêu rõ lý do cần mở quyền truy cập đặc quyền..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Support Case ID (nếu có):</label>
                <input
                  type="text"
                  style={{ width: '100%' }}
                  value={relatedSupportCaseId}
                  onChange={(e) => setRelatedSupportCaseId(e.target.value)}
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Thời lượng (phút):</label>
                <input
                  type="number"
                  min={5}
                  max={240}
                  style={{ width: '100%' }}
                  value={durationMinutes}
                  onChange={(e) => setDurationMinutes(Number(e.target.value))}
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Mật khẩu quản trị viên (Step-Up):</label>
                <input
                  type="password"
                  required
                  style={{ width: '100%' }}
                  value={stepUpPassword}
                  onChange={(e) => setStepUpPassword(e.target.value)}
                  placeholder="Nhập mật khẩu admin..."
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                <button
                  type="button"
                  className="button-secondary"
                  disabled={submitting}
                  onClick={() => setShowModal(false)}
                >
                  Hủy
                </button>
                <button type="submit" className="button-primary" disabled={submitting}>
                  {submitting ? 'Đang kích hoạt...' : 'Kích hoạt phiên'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
