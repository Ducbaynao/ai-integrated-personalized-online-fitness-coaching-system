import { useEffect, useState } from 'react'
import { privilegedAccessApi } from '../../services/supportModerationAuditApi.ts'
import type { PrivilegedAccess } from '../../types/supportModerationAudit.ts'

export function PrivilegedAccessBanner() {
  const [activeAccess, setActiveAccess] = useState<PrivilegedAccess | null>(null)
  const [revoking, setRevoking] = useState(false)
  const [showRevokeInput, setShowRevokeInput] = useState(false)
  const [revokeReason, setRevokeReason] = useState('')

  const fetchActive = async () => {
    try {
      const active = await privilegedAccessApi.getActive()
      setActiveAccess(active)
    } catch {
      setActiveAccess(null)
    }
  }

  useEffect(() => {
    void fetchActive()
    const timer = setInterval(() => {
      void fetchActive()
    }, 15000)
    return () => clearInterval(timer)
  }, [])

  if (!activeAccess || !activeAccess.currentlyActive) {
    return null
  }

  const handleRevoke = async () => {
    if (!revokeReason.trim()) return
    setRevoking(true)
    try {
      await privilegedAccessApi.revokeAccess(activeAccess.id, { reason: revokeReason.trim() })
      setActiveAccess(null)
      setShowRevokeInput(false)
      setRevokeReason('')
    } catch (err) {
      alert('Không thể thu hồi phiên: ' + (err instanceof Error ? err.message : String(err)))
    } finally {
      setRevoking(false)
    }
  }

  return (
    <div
      role="alert"
      className="privileged-access-banner"
      style={{
        backgroundColor: '#7f1d1d',
        color: '#fef2f2',
        padding: '0.75rem 1.25rem',
        borderRadius: '6px',
        marginBottom: '1rem',
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: '0.75rem',
      }}
    >
      <div>
        <strong>⚠️ PHIÊN TRUY CẬP ĐẶC QUYỀN ĐANG HOẠT ĐỘNG</strong>
        <div style={{ fontSize: '0.875rem', marginTop: '0.25rem' }}>
          Mục đích: {activeAccess.purpose} | Đối tượng: {activeAccess.targetUserName ?? activeAccess.targetUserId} | Hết hạn lúc:{' '}
          {activeAccess.expiresAt ? new Date(activeAccess.expiresAt).toLocaleTimeString() : 'N/A'}
        </div>
      </div>

      {!showRevokeInput ? (
        <button
          type="button"
          className="button-secondary"
          style={{ backgroundColor: '#fff', color: '#7f1d1d', fontWeight: 'bold' }}
          onClick={() => setShowRevokeInput(true)}
        >
          Thu hồi phiên ngay
        </button>
      ) : (
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <input
            type="text"
            placeholder="Lý do thu hồi..."
            value={revokeReason}
            onChange={(e) => setRevokeReason(e.target.value)}
            style={{ padding: '0.4rem 0.6rem', borderRadius: '4px', border: '1px solid #ccc' }}
          />
          <button
            type="button"
            className="button-danger"
            disabled={revoking || !revokeReason.trim()}
            onClick={() => void handleRevoke()}
          >
            {revoking ? 'Đang thu hồi...' : 'Xác nhận thu hồi'}
          </button>
          <button
            type="button"
            className="button-secondary"
            disabled={revoking}
            onClick={() => setShowRevokeInput(false)}
          >
            Hủy
          </button>
        </div>
      )}
    </div>
  )
}
