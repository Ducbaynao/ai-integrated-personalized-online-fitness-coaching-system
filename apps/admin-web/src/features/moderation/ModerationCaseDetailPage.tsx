import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { moderationApi } from '../../services/supportModerationAuditApi.ts'
import type { ModerationCaseDetail } from '../../types/supportModerationAudit.ts'

export function ModerationCaseDetailPage() {
  const { caseId } = useParams<{ caseId: string }>()
  const [detail, setDetail] = useState<ModerationCaseDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Status update
  const [newStatus, setNewStatus] = useState('')
  const [statusNotes, setStatusNotes] = useState('')

  // Action form
  const [actionType, setActionType] = useState('WARNING')
  const [actionReason, setActionReason] = useState('')
  const [stepUpPassword, setStepUpPassword] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const loadCase = async () => {
    if (!caseId) return
    setLoading(true)
    setError(null)
    try {
      const res = await moderationApi.getModerationCaseDetail(caseId)
      setDetail(res)
      setNewStatus(res.summary.status)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadCase()
  }, [caseId])

  const handleStatusUpdate = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!caseId || !newStatus) return
    setActionBusy(true)
    setActionError(null)
    try {
      const res = await moderationApi.updateStatus(caseId, {
        status: newStatus,
        notes: statusNotes.trim() || undefined,
      })
      setDetail(res)
      setStatusNotes('')
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    } finally {
      setActionBusy(false)
    }
  }

  const handlePerformAction = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!caseId || !actionReason.trim()) return
    setActionBusy(true)
    setActionError(null)
    try {
      await moderationApi.performAction(caseId, {
        actionType,
        targetUserId: detail?.summary.targetType === 'USER' ? detail.summary.targetId : undefined,
        reason: actionReason.trim(),
        stepUpPassword: stepUpPassword.trim() || undefined,
      })
      await loadCase()
      setActionReason('')
      setStepUpPassword('')
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    } finally {
      setActionBusy(false)
    }
  }

  if (loading) return <div>Đang tải chi tiết case kiểm duyệt...</div>
  if (error || !detail) return <div style={{ color: '#dc2626' }}>{error ?? 'Không tìm thấy case kiểm duyệt'}</div>

  const { summary, actions } = detail

  return (
    <div className="moderation-case-detail-page">
      <div style={{ marginBottom: '1rem' }}>
        <Link to="/moderation-cases">← Quay lại danh sách case kiểm duyệt</Link>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2>Case kiểm duyệt: {summary.targetType}</h2>
          <h4 style={{ margin: '0.25rem 0', color: '#374151' }}>
            Đối tượng: <strong>{summary.targetDisplayName ?? summary.targetId}</strong>
          </h4>
          <span style={{ fontSize: '0.85rem', color: '#6b7280' }}>
            Lý do ban đầu: {summary.createdReason}
          </span>
        </div>
        <div>
          <span
            style={{
              padding: '0.3rem 0.8rem',
              borderRadius: '6px',
              fontWeight: 'bold',
              backgroundColor: '#fef3c7',
              color: '#92400e',
            }}
          >
            {summary.status}
          </span>
        </div>
      </div>

      {actionError && (
        <div style={{ padding: '0.75rem', backgroundColor: '#fee2e2', color: '#dc2626', borderRadius: '4px', marginBottom: '1rem' }}>
          {actionError}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '1.5rem' }}>
        {/* Left Column: Append-only Action Log */}
        <div>
          <div className="card" style={{ padding: '1.5rem', marginBottom: '1.5rem' }}>
            <h3>Lịch sử quyết định xử lý (Append-only Moderation Actions)</h3>
            {actions.length === 0 && <p>Chưa có hành động xử lý nào.</p>}
            <div style={{ marginTop: '1rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              {actions.map((act) => (
                <div
                  key={act.id}
                  style={{
                    padding: '0.75rem',
                    backgroundColor: '#f9fafb',
                    borderRadius: '6px',
                    borderLeft: `4px solid ${
                      act.actionType === 'SUSPENSION' || act.actionType === 'DISABLE'
                        ? '#dc2626'
                        : act.actionType === 'WARNING'
                          ? '#f59e0b'
                          : '#10b981'
                    }`,
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', color: '#6b7280' }}>
                    <strong>{act.actionType}</strong>
                    <span>{new Date(act.performedAt).toLocaleString()}</span>
                  </div>
                  <div style={{ marginTop: '0.25rem', fontWeight: '500' }}>{act.reason}</div>
                  <div style={{ fontSize: '0.8rem', color: '#9ca3af', marginTop: '0.25rem' }}>
                    Thực hiện bởi: {act.performedByAdminName ?? act.performedByAdminId ?? 'Hệ thống'}
                    {act.targetUserName && ` | Áp dụng cho: ${act.targetUserName}`}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Right Column: Perform Action and Status Update */}
        <div>
          {/* Perform Moderation Action */}
          <div className="card" style={{ padding: '1.25rem', marginBottom: '1rem' }}>
            <h4>Thực hiện hành động kiểm duyệt</h4>
            <form onSubmit={handlePerformAction} style={{ marginTop: '0.75rem' }}>
              <label style={{ fontSize: '0.85rem' }}>Hành động:</label>
              <select
                value={actionType}
                onChange={(e) => setActionType(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              >
                <option value="WARNING">Cảnh cáo (WARNING)</option>
                <option value="SUSPENSION">Tạm đình chỉ tài khoản (SUSPENSION)</option>
                <option value="DISABLE">Vô hiệu hóa vĩnh viễn (DISABLE)</option>
                <option value="CONTENT_REMOVAL">Gỡ bỏ nội dung (CONTENT_REMOVAL)</option>
                <option value="NO_ACTION">Không vi phạm / Đóng case (NO_ACTION)</option>
              </select>

              <label style={{ fontSize: '0.85rem' }}>Lý do (bắt buộc):</label>
              <textarea
                placeholder="Nêu rõ lý do áp dụng chế tài..."
                rows={3}
                required
                value={actionReason}
                onChange={(e) => setActionReason(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              />

              {(actionType === 'SUSPENSION' || actionType === 'DISABLE') && (
                <div style={{ marginBottom: '0.5rem' }}>
                  <label style={{ fontSize: '0.85rem', color: '#dc2626' }}>Mật khẩu Admin (Step-Up):</label>
                  <input
                    type="password"
                    placeholder="Xác thực mật khẩu quản trị..."
                    value={stepUpPassword}
                    onChange={(e) => setStepUpPassword(e.target.value)}
                    style={{ width: '100%' }}
                    disabled={actionBusy}
                  />
                </div>
              )}

              <button
                type="submit"
                className={actionType === 'DISABLE' || actionType === 'SUSPENSION' ? 'button-danger' : 'button-primary'}
                disabled={actionBusy || !actionReason.trim()}
              >
                Xác nhận hành động
              </button>
            </form>
          </div>

          {/* Update Status Card */}
          <div className="card" style={{ padding: '1.25rem' }}>
            <h4>Chuyển trạng thái case</h4>
            <form onSubmit={handleStatusUpdate} style={{ marginTop: '0.75rem' }}>
              <select
                value={newStatus}
                onChange={(e) => setNewStatus(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              >
                <option value="OPEN">OPEN (Mở)</option>
                <option value="INVESTIGATING">INVESTIGATING (Đang điều tra)</option>
                <option value="ACTIONED">ACTIONED (Đã chế tài / giải quyết)</option>
                <option value="DISMISSED">DISMISSED (Bác bỏ / không vi phạm)</option>
              </select>
              <textarea
                placeholder="Ghi chú trạng thái..."
                rows={2}
                value={statusNotes}
                onChange={(e) => setStatusNotes(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              />
              <button type="submit" className="button-secondary" disabled={actionBusy || newStatus === summary.status}>
                Cập nhật trạng thái
              </button>
            </form>
          </div>
        </div>
      </div>
    </div>
  )
}
