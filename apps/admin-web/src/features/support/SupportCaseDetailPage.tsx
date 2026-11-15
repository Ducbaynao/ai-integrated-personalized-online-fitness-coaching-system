import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { supportApi } from '../../services/supportModerationAuditApi.ts'
import type { SupportCaseDetail } from '../../types/supportModerationAudit.ts'

export function SupportCaseDetailPage() {
  const { caseId } = useParams<{ caseId: string }>()
  const [detail, setDetail] = useState<SupportCaseDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Actions state
  const [newStatus, setNewStatus] = useState('')
  const [statusNote, setStatusNote] = useState('')
  const [internalNote, setInternalNote] = useState('')
  const [assignAdminId, setAssignAdminId] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const loadCase = async () => {
    if (!caseId) return
    setLoading(true)
    setError(null)
    try {
      const res = await supportApi.getSupportCaseDetail(caseId)
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
      const res = await supportApi.updateStatus(caseId, {
        status: newStatus,
        note: statusNote.trim() || undefined,
      })
      setDetail(res)
      setStatusNote('')
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    } finally {
      setActionBusy(false)
    }
  }

  const handleAddNote = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!caseId || !internalNote.trim()) return
    setActionBusy(true)
    setActionError(null)
    try {
      const res = await supportApi.addNote(caseId, { note: internalNote.trim() })
      setDetail(res)
      setInternalNote('')
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    } finally {
      setActionBusy(false)
    }
  }

  const handleAssign = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!caseId || !assignAdminId.trim()) return
    setActionBusy(true)
    setActionError(null)
    try {
      const res = await supportApi.assignCase(caseId, { assignedToAdminId: assignAdminId.trim() })
      setDetail(res)
      setAssignAdminId('')
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    } finally {
      setActionBusy(false)
    }
  }

  if (loading) return <div>Đang tải chi tiết phiếu hỗ trợ...</div>
  if (error || !detail) return <div style={{ color: '#dc2626' }}>{error ?? 'Không tìm thấy phiếu hỗ trợ'}</div>

  const { summary, events } = detail

  return (
    <div className="support-case-detail-page">
      <div style={{ marginBottom: '1rem' }}>
        <Link to="/support-cases">← Quay lại danh sách phiếu</Link>
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2>Phiếu hỗ trợ #{summary.caseNumber}</h2>
          <h4 style={{ margin: '0.25rem 0', color: '#374151' }}>{summary.subject}</h4>
          <span style={{ fontSize: '0.85rem', color: '#6b7280' }}>
            Người yêu cầu: <strong>{summary.requesterName}</strong> ({summary.requesterId})
          </span>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <span
            style={{
              padding: '0.3rem 0.8rem',
              borderRadius: '6px',
              fontWeight: 'bold',
              backgroundColor: '#e0f2fe',
              color: '#0369a1',
            }}
          >
            {summary.status}
          </span>
          <Link
            to={`/data-corrections?supportCaseId=${summary.id}`}
            className="button-primary"
            style={{ fontSize: '0.85rem' }}
          >
            ⚡ Hiệu chỉnh dữ liệu cho phiếu này
          </Link>
        </div>
      </div>

      {actionError && (
        <div style={{ padding: '0.75rem', backgroundColor: '#fee2e2', color: '#dc2626', borderRadius: '4px', marginBottom: '1rem' }}>
          {actionError}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '1.5rem' }}>
        {/* Left column: Event Timeline */}
        <div>
          <div className="card" style={{ padding: '1.5rem', marginBottom: '1.5rem' }}>
            <h3>Lịch sử xử lý & Ghi chú (Append-only Events)</h3>
            {events.length === 0 && <p>Chưa có sự kiện nào.</p>}
            <div style={{ marginTop: '1rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              {events.map((ev) => (
                <div
                  key={ev.id}
                  style={{
                    padding: '0.75rem',
                    backgroundColor: '#f9fafb',
                    borderRadius: '6px',
                    borderLeft: '4px solid #3b82f6',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8rem', color: '#6b7280' }}>
                    <strong>{ev.eventType}</strong>
                    <span>{new Date(ev.createdAt).toLocaleString()}</span>
                  </div>
                  <div style={{ marginTop: '0.25rem', fontWeight: '500' }}>{ev.notes}</div>
                  <div style={{ fontSize: '0.8rem', color: '#9ca3af', marginTop: '0.25rem' }}>
                    Bởi: {ev.actorName ?? ev.actorUserId ?? 'Hệ thống'}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Right column: Action Controls */}
        <div>
          {/* Update Status Card */}
          <div className="card" style={{ padding: '1.25rem', marginBottom: '1rem' }}>
            <h4>Chuyển trạng thái</h4>
            <form onSubmit={handleStatusUpdate} style={{ marginTop: '0.75rem' }}>
              <select
                value={newStatus}
                onChange={(e) => setNewStatus(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              >
                <option value="OPEN">OPEN (Mở)</option>
                <option value="IN_PROGRESS">IN_PROGRESS (Đang xử lý)</option>
                <option value="RESOLVED">RESOLVED (Đã giải quyết)</option>
                <option value="CLOSED">CLOSED (Đóng phiếu)</option>
              </select>
              <textarea
                placeholder="Ghi chú chuyển trạng thái..."
                rows={2}
                value={statusNote}
                onChange={(e) => setStatusNote(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              />
              <button type="submit" className="button-secondary" disabled={actionBusy || newStatus === summary.status}>
                Cập nhật trạng thái
              </button>
            </form>
          </div>

          {/* Add Internal Note */}
          <div className="card" style={{ padding: '1.25rem', marginBottom: '1rem' }}>
            <h4>Thêm ghi chú nội bộ</h4>
            <form onSubmit={handleAddNote} style={{ marginTop: '0.75rem' }}>
              <textarea
                placeholder="Nội dung ghi chú..."
                rows={3}
                value={internalNote}
                onChange={(e) => setInternalNote(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              />
              <button type="submit" className="button-secondary" disabled={actionBusy || !internalNote.trim()}>
                Lưu ghi chú
              </button>
            </form>
          </div>

          {/* Assign Case */}
          <div className="card" style={{ padding: '1.25rem' }}>
            <h4>Phân công người phụ trách</h4>
            <p style={{ fontSize: '0.8rem', color: '#6b7280' }}>
              Hiện tại: {summary.assignedToAdminName ?? 'Chưa phân công'}
            </p>
            <form onSubmit={handleAssign} style={{ marginTop: '0.75rem' }}>
              <input
                type="text"
                placeholder="UUID Admin phụ trách..."
                value={assignAdminId}
                onChange={(e) => setAssignAdminId(e.target.value)}
                style={{ width: '100%', marginBottom: '0.5rem' }}
                disabled={actionBusy}
              />
              <button type="submit" className="button-secondary" disabled={actionBusy || !assignAdminId.trim()}>
                Phân công
              </button>
            </form>
          </div>
        </div>
      </div>
    </div>
  )
}
