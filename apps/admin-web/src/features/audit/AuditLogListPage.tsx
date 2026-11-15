import { useEffect, useState } from 'react'
import { auditApi } from '../../services/supportModerationAuditApi.ts'
import type { AuditLogEntry } from '../../types/supportModerationAudit.ts'

export function AuditLogListPage() {
  const [logs, setLogs] = useState<AuditLogEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Filters
  const [actionFilter, setActionFilter] = useState('')
  const [targetTypeFilter, setTargetTypeFilter] = useState('')
  const [actorRoleFilter, setActorRoleFilter] = useState('')
  const [targetIdFilter, setTargetIdFilter] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  // Detail modal
  const [selectedLog, setSelectedLog] = useState<AuditLogEntry | null>(null)

  const loadData = async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await auditApi.getAuditLogs({
        action: actionFilter.trim() || undefined,
        targetType: targetTypeFilter.trim() || undefined,
        actorRole: actorRoleFilter || undefined,
        targetId: targetIdFilter.trim() || undefined,
        page,
        size: 20,
      })
      setLogs(res.content)
      setTotalPages(res.totalPages)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadData()
  }, [actorRoleFilter, page])

  const handleFilterSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setPage(0)
    void loadData()
  }

  const formatJson = (jsonStr: string | null) => {
    if (!jsonStr) return 'null'
    try {
      return JSON.stringify(JSON.parse(jsonStr), null, 2)
    } catch {
      return jsonStr
    }
  }

  return (
    <div className="audit-log-list-page">
      <div style={{ marginBottom: '1.5rem' }}>
        <h2>Nhật ký kiểm toán hệ thống (Audit Logs)</h2>
        <p style={{ color: '#6b7280' }}>
          Tra cứu toàn bộ thao tác quản trị, xử lý kiểm duyệt và hiệu chỉnh dữ liệu. Nhật ký là bất biến (không hỗ trợ sửa hoặc xóa).
          Các thông tin nhạy cảm (mật khẩu, khóa bảo mật) được che giấu tự động theo tiêu chuẩn bảo vệ quyền riêng tư.
        </p>
      </div>

      <form onSubmit={handleFilterSubmit} style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
        <input
          type="text"
          placeholder="Lọc theo Hành động (Action)..."
          value={actionFilter}
          onChange={(e) => setActionFilter(e.target.value)}
          style={{ minWidth: '180px' }}
        />
        <input
          type="text"
          placeholder="Loại đối tượng (USER, SUPPORT_CASE...)"
          value={targetTypeFilter}
          onChange={(e) => setTargetTypeFilter(e.target.value)}
          style={{ minWidth: '180px' }}
        />
        <input
          type="text"
          placeholder="Mã đối tượng (Target ID UUID)..."
          value={targetIdFilter}
          onChange={(e) => setTargetIdFilter(e.target.value)}
          style={{ minWidth: '220px' }}
        />
        <select
          value={actorRoleFilter}
          onChange={(e) => {
            setActorRoleFilter(e.target.value)
            setPage(0)
          }}
          aria-label="Lọc theo vai trò thực hiện"
        >
          <option value="">Tất cả vai trò</option>
          <option value="ADMIN">ADMIN</option>
          <option value="TRAINER">TRAINER</option>
          <option value="STUDENT">STUDENT</option>
          <option value="SYSTEM">SYSTEM</option>
        </select>
        <button type="submit" className="button-secondary">
          Lọc kết quả
        </button>
      </form>

      {loading && <p>Đang tải nhật ký kiểm toán...</p>}
      {error && <div className="error-message" style={{ color: '#dc2626' }}>{error}</div>}

      {!loading && !error && logs.length === 0 && (
        <p>Không có bản ghi nhật ký kiểm toán nào khớp với bộ lọc.</p>
      )}

      {!loading && !error && logs.length > 0 && (
        <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ textAlign: 'left', borderBottom: '2px solid #e5e7eb' }}>
              <th style={{ padding: '0.75rem' }}>Thời gian</th>
              <th style={{ padding: '0.75rem' }}>Người thực hiện</th>
              <th style={{ padding: '0.75rem' }}>Vai trò</th>
              <th style={{ padding: '0.75rem' }}>Hành động</th>
              <th style={{ padding: '0.75rem' }}>Đối tượng</th>
              <th style={{ padding: '0.75rem' }}>IP / User Agent</th>
              <th style={{ padding: '0.75rem' }}>Chi tiết</th>
            </tr>
          </thead>
          <tbody>
            {logs.map((log) => (
              <tr key={log.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                <td style={{ padding: '0.75rem', fontSize: '0.85rem' }}>
                  {new Date(log.occurredAt).toLocaleString()}
                </td>
                <td style={{ padding: '0.75rem' }}>
                  <strong>{log.actorName ?? (log.actorUserId ? log.actorUserId.substring(0, 8) : 'Hệ thống')}</strong>
                </td>
                <td style={{ padding: '0.75rem' }}>
                  <span
                    style={{
                      padding: '0.2rem 0.5rem',
                      borderRadius: '4px',
                      fontSize: '0.75rem',
                      fontWeight: 'bold',
                      backgroundColor: log.actorRole === 'ADMIN' ? '#fee2e2' : '#f3f4f6',
                      color: log.actorRole === 'ADMIN' ? '#991b1b' : '#374151',
                    }}
                  >
                    {log.actorRole}
                  </span>
                </td>
                <td style={{ padding: '0.75rem', fontWeight: '500' }}>{log.action}</td>
                <td style={{ padding: '0.75rem', fontSize: '0.85rem' }}>
                  <div>{log.targetType}</div>
                  <div style={{ color: '#6b7280', fontSize: '0.75rem', fontFamily: 'monospace' }}>
                    {log.targetId.substring(0, 8)}...
                  </div>
                </td>
                <td style={{ padding: '0.75rem', fontSize: '0.75rem', color: '#6b7280' }}>
                  {log.ipAddress ?? '-'}
                </td>
                <td style={{ padding: '0.75rem' }}>
                  <button
                    className="button-secondary"
                    style={{ fontSize: '0.8rem' }}
                    onClick={() => setSelectedLog(log)}
                  >
                    Xem JSON
                  </button>
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

      {/* JSON Inspection Modal */}
      {selectedLog && (
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
              maxWidth: '750px',
              width: '100%',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
          >
            <h3>Chi tiết bản ghi kiểm toán #{selectedLog.id}</h3>
            <p style={{ color: '#6b7280', fontSize: '0.85rem' }}>
              {selectedLog.action} | {selectedLog.targetType} ({selectedLog.targetId}) | {new Date(selectedLog.occurredAt).toLocaleString()}
            </p>

            <div style={{ marginTop: '1rem' }}>
              <h4>Dữ liệu trước (Before Data):</h4>
              <pre
                style={{
                  backgroundColor: '#f3f4f6',
                  padding: '0.75rem',
                  borderRadius: '4px',
                  fontSize: '0.8rem',
                  overflowX: 'auto',
                }}
              >
                {formatJson(selectedLog.beforeDataJson)}
              </pre>
            </div>

            <div style={{ marginTop: '1rem' }}>
              <h4>Dữ liệu sau (After Data):</h4>
              <pre
                style={{
                  backgroundColor: '#f3f4f6',
                  padding: '0.75rem',
                  borderRadius: '4px',
                  fontSize: '0.8rem',
                  overflowX: 'auto',
                }}
              >
                {formatJson(selectedLog.afterDataJson)}
              </pre>
            </div>

            <div style={{ marginTop: '1rem' }}>
              <h4>Metadata / Thông tin bổ sung:</h4>
              <pre
                style={{
                  backgroundColor: '#f3f4f6',
                  padding: '0.75rem',
                  borderRadius: '4px',
                  fontSize: '0.8rem',
                  overflowX: 'auto',
                }}
              >
                {formatJson(selectedLog.metadataJson)}
              </pre>
            </div>

            <div style={{ marginTop: '1.5rem', display: 'flex', justifyContent: 'flex-end' }}>
              <button className="button-secondary" onClick={() => setSelectedLog(null)}>
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
