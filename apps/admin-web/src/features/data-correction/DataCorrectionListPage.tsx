import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { dataCorrectionApi, privilegedAccessApi } from '../../services/supportModerationAuditApi.ts'
import type { DataCorrection, PrivilegedAccess } from '../../types/supportModerationAudit.ts'

export function DataCorrectionListPage() {
  const [searchParams] = useSearchParams()
  const initialSupportCaseId = searchParams.get('supportCaseId') ?? ''

  const [corrections, setCorrections] = useState<DataCorrection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [supportCaseFilter, setSupportCaseFilter] = useState(initialSupportCaseId)
  const [targetTypeFilter, setTargetTypeFilter] = useState('')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  // Selected correction for diff inspect
  const [selectedCorrection, setSelectedCorrection] = useState<DataCorrection | null>(null)

  // Execute correction modal
  const [showExecuteModal, setShowExecuteModal] = useState(false)
  const [supportCaseId, setSupportCaseId] = useState(initialSupportCaseId)
  const [targetType, setTargetType] = useState('MEASUREMENT')
  const [targetId, setTargetId] = useState('')
  const [reason, setReason] = useState('')
  const [proposedValue, setProposedValue] = useState('')
  const [proposedUnit, setProposedUnit] = useState('KG')
  const [proposedDisplayName, setProposedDisplayName] = useState('')
  const [proposedPhone, setProposedPhone] = useState('')
  const [privilegedAccessRequestId, setPrivilegedAccessRequestId] = useState('')
  const [stepUpPassword, setStepUpPassword] = useState('')
  const [activePrivileged, setActivePrivileged] = useState<PrivilegedAccess | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [modalError, setModalError] = useState<string | null>(null)

  const loadData = async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await dataCorrectionApi.getCorrections({
        supportCaseId: supportCaseFilter.trim() || undefined,
        targetType: targetTypeFilter || undefined,
        page,
        size: 15,
      })
      setCorrections(res.content)
      setTotalPages(res.totalPages)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadData()
  }, [supportCaseFilter, targetTypeFilter, page])

  const openExecuteModal = async () => {
    setShowExecuteModal(true)
    setModalError(null)
    try {
      const active = await privilegedAccessApi.getActive()
      if (active && active.currentlyActive) {
        setActivePrivileged(active)
        setPrivilegedAccessRequestId(active.id)
        if (active.relatedSupportCaseId && !supportCaseId) {
          setSupportCaseId(active.relatedSupportCaseId)
        }
      } else {
        setActivePrivileged(null)
      }
    } catch {
      setActivePrivileged(null)
    }
  }

  const handleExecute = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setModalError(null)

    const proposedData: Record<string, unknown> = {}
    if (targetType === 'MEASUREMENT') {
      const val = parseFloat(proposedValue)
      if (isNaN(val) || val <= 0) {
        setModalError('Số đo phải là một số lớn hơn 0')
        setSubmitting(false)
        return
      }
      proposedData.value = val
      if (proposedUnit.trim()) {
        proposedData.unit = proposedUnit.trim()
      }
    } else if (targetType === 'USER_PROFILE') {
      if (!proposedDisplayName.trim()) {
        setModalError('Tên hiển thị là bắt buộc')
        setSubmitting(false)
        return
      }
      proposedData.displayName = proposedDisplayName.trim()
      if (proposedPhone.trim()) {
        proposedData.phoneNumber = proposedPhone.trim()
      }
    }

    try {
      await dataCorrectionApi.executeCorrection({
        supportCaseId: supportCaseId.trim(),
        targetType,
        targetId: targetId.trim(),
        reason: reason.trim(),
        proposedData,
        privilegedAccessRequestId: privilegedAccessRequestId.trim(),
        stepUpPassword: stepUpPassword.trim() || undefined,
      })
      setShowExecuteModal(false)
      setTargetId('')
      setReason('')
      setProposedValue('')
      setStepUpPassword('')
      void loadData()
    } catch (err) {
      setModalError(err instanceof Error ? err.message : String(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="data-correction-list-page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div>
          <h2>Hiệu chỉnh dữ liệu quản trị (Governed Data Correction)</h2>
          <p style={{ color: '#6b7280' }}>
            Điều chỉnh dữ liệu nghiệp vụ có kiểm soát: yêu cầu phiếu hỗ trợ, phiên truy cập đặc quyền, xác thực step-up và bảo toàn lịch sử.
          </p>
        </div>
        <button className="button-primary" onClick={() => void openExecuteModal()}>
          ⚡ Thực hiện hiệu chỉnh dữ liệu
        </button>
      </div>

      <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
        <input
          type="text"
          placeholder="Lọc theo Support Case ID..."
          value={supportCaseFilter}
          onChange={(e) => {
            setSupportCaseFilter(e.target.value)
            setPage(0)
          }}
          style={{ minWidth: '260px' }}
        />
        <select
          value={targetTypeFilter}
          onChange={(e) => {
            setTargetTypeFilter(e.target.value)
            setPage(0)
          }}
          aria-label="Lọc theo loại dữ liệu"
        >
          <option value="">Tất cả loại dữ liệu</option>
          <option value="MEASUREMENT">Số đo học viên (MEASUREMENT)</option>
          <option value="USER_PROFILE">Hồ sơ người dùng (USER_PROFILE)</option>
        </select>
        <button className="button-secondary" onClick={() => void loadData()}>
          Làm mới
        </button>
      </div>

      {loading && <p>Đang tải lịch sử hiệu chỉnh...</p>}
      {error && <div className="error-message" style={{ color: '#dc2626' }}>{error}</div>}

      {!loading && !error && corrections.length === 0 && (
        <p>Chưa có hành động hiệu chỉnh dữ liệu nào được ghi nhận.</p>
      )}

      {!loading && !error && corrections.length > 0 && (
        <table className="data-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr style={{ textAlign: 'left', borderBottom: '2px solid #e5e7eb' }}>
              <th style={{ padding: '0.75rem' }}>Loại dữ liệu</th>
              <th style={{ padding: '0.75rem' }}>Mã đối tượng (Target ID)</th>
              <th style={{ padding: '0.75rem' }}>Phiếu hỗ trợ</th>
              <th style={{ padding: '0.75rem' }}>Admin thực hiện</th>
              <th style={{ padding: '0.75rem' }}>Thời gian thực hiện</th>
              <th style={{ padding: '0.75rem' }}>Chi tiết Before/After</th>
            </tr>
          </thead>
          <tbody>
            {corrections.map((corr) => (
              <tr key={corr.id} style={{ borderBottom: '1px solid #e5e7eb' }}>
                <td style={{ padding: '0.75rem' }}>
                  <span
                    style={{
                      padding: '0.2rem 0.5rem',
                      borderRadius: '4px',
                      fontSize: '0.75rem',
                      fontWeight: 'bold',
                      backgroundColor: corr.targetType === 'MEASUREMENT' ? '#eff6ff' : '#fdf2f8',
                      color: corr.targetType === 'MEASUREMENT' ? '#1d4ed8' : '#be185d',
                    }}
                  >
                    {corr.targetType}
                  </span>
                </td>
                <td style={{ padding: '0.75rem', fontFamily: 'monospace', fontSize: '0.85rem' }}>
                  {corr.targetId}
                </td>
                <td style={{ padding: '0.75rem' }}>
                  {corr.caseNumber ?? corr.supportCaseId.substring(0, 8)}
                </td>
                <td style={{ padding: '0.75rem' }}>{corr.appliedByName ?? corr.appliedBy}</td>
                <td style={{ padding: '0.75rem', fontSize: '0.85rem' }}>
                  {corr.appliedAt ? new Date(corr.appliedAt).toLocaleString() : new Date(corr.createdAt).toLocaleString()}
                </td>
                <td style={{ padding: '0.75rem' }}>
                  <button
                    className="button-secondary"
                    style={{ fontSize: '0.8rem' }}
                    onClick={() => setSelectedCorrection(corr)}
                  >
                    Xem biến động dữ liệu
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

      {/* Before / After Detail Modal */}
      {selectedCorrection && (
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
              maxWidth: '700px',
              width: '100%',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
          >
            <h3>Chi tiết hiệu chỉnh: {selectedCorrection.targetType}</h3>
            <p style={{ color: '#6b7280', fontSize: '0.85rem' }}>
              Phiếu hỗ trợ: {selectedCorrection.caseNumber ?? selectedCorrection.supportCaseId} | Người thực hiện:{' '}
              {selectedCorrection.appliedByName}
            </p>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
              <div>
                <h4>Dữ liệu gốc (Before):</h4>
                <pre
                  style={{
                    backgroundColor: '#fee2e2',
                    padding: '0.75rem',
                    borderRadius: '4px',
                    fontSize: '0.8rem',
                    overflowX: 'auto',
                  }}
                >
                  {JSON.stringify(JSON.parse(selectedCorrection.beforeDataJson || '{}'), null, 2)}
                </pre>
              </div>
              <div>
                <h4>Dữ liệu áp dụng mới (Applied):</h4>
                <pre
                  style={{
                    backgroundColor: '#dcfce7',
                    padding: '0.75rem',
                    borderRadius: '4px',
                    fontSize: '0.8rem',
                    overflowX: 'auto',
                  }}
                >
                  {JSON.stringify(JSON.parse(selectedCorrection.appliedDataJson || '{}'), null, 2)}
                </pre>
              </div>
            </div>

            <div style={{ marginTop: '1rem' }}>
              <h4>Kết quả xác thực:</h4>
              <pre
                style={{
                  backgroundColor: '#f3f4f6',
                  padding: '0.5rem',
                  borderRadius: '4px',
                  fontSize: '0.8rem',
                }}
              >
                {selectedCorrection.validationResultJson}
              </pre>
            </div>

            <div style={{ marginTop: '1.5rem', display: 'flex', justifyContent: 'flex-end' }}>
              <button className="button-secondary" onClick={() => setSelectedCorrection(null)}>
                Đóng
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Execute Correction Modal */}
      {showExecuteModal && (
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
              maxWidth: '550px',
              width: '100%',
              maxHeight: '90vh',
              overflowY: 'auto',
            }}
          >
            <h3>⚡ Thực hiện hiệu chỉnh dữ liệu quản trị</h3>
            {modalError && <div style={{ color: '#dc2626', marginBottom: '1rem' }}>{modalError}</div>}

            {!activePrivileged && (
              <div
                style={{
                  padding: '0.75rem',
                  backgroundColor: '#fee2e2',
                  color: '#b91c1c',
                  borderRadius: '6px',
                  marginBottom: '1rem',
                  fontSize: '0.85rem',
                }}
              >
                <strong>Cảnh báo:</strong> Bạn hiện chưa có Phiên truy cập đặc quyền (Privileged Access) nào đang hoạt động.
                Hãy tạo hoặc kích hoạt phiên truy cập trước khi thực hiện hiệu chỉnh dữ liệu.
              </div>
            )}

            <form onSubmit={(e) => void handleExecute(e)}>
              <div style={{ marginBottom: '1rem' }}>
                <label>Phiếu hỗ trợ (Support Case ID):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={supportCaseId}
                  onChange={(e) => setSupportCaseId(e.target.value)}
                  placeholder="UUID của phiếu hỗ trợ..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Mã phiên truy cập đặc quyền (Privileged Access Request ID):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={privilegedAccessRequestId}
                  onChange={(e) => setPrivilegedAccessRequestId(e.target.value)}
                  placeholder="UUID phiên truy cập đặc quyền ACTIVE..."
                />
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Loại dữ liệu cần hiệu chỉnh:</label>
                <select
                  style={{ width: '100%' }}
                  value={targetType}
                  onChange={(e) => setTargetType(e.target.value)}
                >
                  <option value="MEASUREMENT">Số đo học viên (MEASUREMENT)</option>
                  <option value="USER_PROFILE">Hồ sơ người dùng (USER_PROFILE)</option>
                </select>
              </div>

              <div style={{ marginBottom: '1rem' }}>
                <label>Target ID (UUID bản ghi cần sửa):</label>
                <input
                  type="text"
                  required
                  style={{ width: '100%' }}
                  value={targetId}
                  onChange={(e) => setTargetId(e.target.value)}
                  placeholder="UUID của số đo hoặc người dùng..."
                />
              </div>

              {targetType === 'MEASUREMENT' && (
                <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1rem' }}>
                  <div style={{ flex: 2 }}>
                    <label>Giá trị mới (Value):</label>
                    <input
                      type="number"
                      step="any"
                      required
                      style={{ width: '100%' }}
                      value={proposedValue}
                      onChange={(e) => setProposedValue(e.target.value)}
                      placeholder="VD: 72.5"
                    />
                  </div>
                  <div style={{ flex: 1 }}>
                    <label>Đơn vị (Unit):</label>
                    <input
                      type="text"
                      required
                      style={{ width: '100%' }}
                      value={proposedUnit}
                      onChange={(e) => setProposedUnit(e.target.value)}
                      placeholder="KG, CM..."
                    />
                  </div>
                </div>
              )}

              {targetType === 'USER_PROFILE' && (
                <div style={{ marginBottom: '1rem' }}>
                  <label>Tên hiển thị mới:</label>
                  <input
                    type="text"
                    required
                    style={{ width: '100%', marginBottom: '0.5rem' }}
                    value={proposedDisplayName}
                    onChange={(e) => setProposedDisplayName(e.target.value)}
                  />
                  <label>Số điện thoại mới (tùy chọn):</label>
                  <input
                    type="text"
                    style={{ width: '100%' }}
                    value={proposedPhone}
                    onChange={(e) => setProposedPhone(e.target.value)}
                  />
                </div>
              )}

              <div style={{ marginBottom: '1rem' }}>
                <label>Lý do hiệu chỉnh (bắt buộc):</label>
                <textarea
                  required
                  rows={2}
                  style={{ width: '100%' }}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Nêu rõ lý do và căn cứ hiệu chỉnh..."
                />
              </div>

              <div style={{ marginBottom: '1.5rem' }}>
                <label style={{ color: '#dc2626' }}>Mật khẩu quản trị viên (Step-Up):</label>
                <input
                  type="password"
                  required
                  style={{ width: '100%' }}
                  value={stepUpPassword}
                  onChange={(e) => setStepUpPassword(e.target.value)}
                  placeholder="Xác thực mật khẩu admin..."
                />
              </div>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
                <button
                  type="button"
                  className="button-secondary"
                  disabled={submitting}
                  onClick={() => setShowExecuteModal(false)}
                >
                  Hủy
                </button>
                <button type="submit" className="button-primary" disabled={submitting}>
                  {submitting ? 'Đang áp dụng...' : 'Áp dụng hiệu chỉnh'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
