import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'
import { trainerApplicationApi } from '../../services/trainerApplicationApi.ts'
import { ApiError } from '../../services/apiClient.ts'
import type { TrainerVerificationStatus } from '../../types/trainerApplication.ts'

export function TrainerApplicationDetailPage() {
  const { applicationId } = useParams<{ applicationId: string }>()
  const client = useQueryClient()

  const [approveOpen, setApproveOpen] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [reviewNotes, setReviewNotes] = useState('')
  const [rejectionReason, setRejectionReason] = useState('')
  const [rejectionError, setRejectionError] = useState<string | null>(null)
  const [isBusy, setIsBusy] = useState(false)
  const [alertMessage, setAlertMessage] = useState<{ text: string; tone: 'success' | 'danger' } | null>(null)

  const { data: application, isLoading, isError, error } = useQuery({
    queryKey: ['admin-trainer-application', applicationId],
    queryFn: () => trainerApplicationApi.getApplicationDetail(applicationId!),
    enabled: Boolean(applicationId),
    retry: false,
  })

  const getStatusBadgeClass = (status: TrainerVerificationStatus) => {
    switch (status) {
      case 'VERIFIED':
        return 'badge-success'
      case 'PENDING':
        return 'badge-warning'
      case 'REJECTED':
      case 'SUSPENDED':
        return 'badge-danger'
      default:
        return 'badge-neutral'
    }
  }

  const getStatusLabel = (status: TrainerVerificationStatus) => {
    switch (status) {
      case 'VERIFIED':
        return 'Đã phê duyệt (VERIFIED)'
      case 'PENDING':
        return 'Chờ xét duyệt (PENDING)'
      case 'REJECTED':
        return 'Đã từ chối (REJECTED)'
      case 'SUSPENDED':
        return 'Đình chỉ (SUSPENDED)'
      default:
        return status
    }
  }

  const handleApprove = async () => {
    if (!applicationId || isBusy) return
    setIsBusy(true)
    setAlertMessage(null)

    try {
      await trainerApplicationApi.decideApplication(applicationId, {
        decision: 'APPROVE',
        reviewNotes: reviewNotes.trim() || undefined,
      })
      setAlertMessage({
        text: 'Phê duyệt hồ sơ HLV thành công. Quyền huấn luyện đã được cấp và đã ghi vết audit.',
        tone: 'success',
      })
      setApproveOpen(false)
      setReviewNotes('')
      await client.invalidateQueries({ queryKey: ['admin-trainer-application', applicationId] })
      await client.invalidateQueries({ queryKey: ['admin-trainer-applications'] })
    } catch (err) {
      const msg = err instanceof ApiError ? `Lỗi: ${err.errorCode}` : 'Không thể phê duyệt hồ sơ.'
      setAlertMessage({ text: msg, tone: 'danger' })
    } finally {
      setIsBusy(false)
    }
  }

  const handleReject = async () => {
    if (!applicationId || isBusy) return
    const trimmedReason = rejectionReason.trim()
    if (!trimmedReason) {
      setRejectionError('Lý do từ chối không được để trống.')
      return
    }

    setIsBusy(true)
    setAlertMessage(null)

    try {
      await trainerApplicationApi.decideApplication(applicationId, {
        decision: 'REJECT',
        rejectionReason: trimmedReason,
        reviewNotes: reviewNotes.trim() || undefined,
      })
      setAlertMessage({
        text: 'Đã từ chối hồ sơ HLV và ghi nhận lý do từ chối. Đã ghi vết audit.',
        tone: 'success',
      })
      setRejectOpen(false)
      setRejectionReason('')
      setReviewNotes('')
      setRejectionError(null)
      await client.invalidateQueries({ queryKey: ['admin-trainer-application', applicationId] })
      await client.invalidateQueries({ queryKey: ['admin-trainer-applications'] })
    } catch (err) {
      const msg = err instanceof ApiError ? `Lỗi: ${err.errorCode}` : 'Không thể từ chối hồ sơ.'
      setAlertMessage({ text: msg, tone: 'danger' })
    } finally {
      setIsBusy(false)
    }
  }

  if (isLoading) {
    return <StatePanel title="Đang tải chi tiết hồ sơ…" />
  }

  if (isError || !application) {
    return (
      <StatePanel
        title="Không tìm thấy hồ sơ"
        description={error instanceof Error ? error.message : 'Hồ sơ không tồn tại hoặc đã bị xóa.'}
        tone="danger"
        action={
          <Link to="/trainer-applications" className="button-secondary">
            ← Quay lại danh sách
          </Link>
        }
      />
    )
  }

  return (
    <div className="page-container">
      {/* Navigation breadcrumbs */}
      <nav aria-label="Breadcrumb" className="breadcrumb">
        <Link to="/trainer-applications">Hồ sơ HLV</Link>
        <span aria-hidden="true"> / </span>
        <span aria-current="page">Chi tiết hồ sơ</span>
      </nav>

      <div className="page-header">
        <div>
          <h1 className="page-title">Chi tiết hồ sơ xác minh HLV</h1>
          <p className="page-subtitle">Mã hồ sơ: {application.id}</p>
        </div>

        {/* Decision Actions for PENDING */}
        {application.status === 'PENDING' && (
          <div className="header-actions">
            <button
              type="button"
              className="button-primary"
              onClick={() => {
                setReviewNotes('')
                setApproveOpen(true)
              }}
            >
              Phê duyệt hồ sơ
            </button>
            <button
              type="button"
              className="button-danger"
              onClick={() => {
                setReviewNotes('')
                setRejectionReason('')
                setRejectionError(null)
                setRejectOpen(true)
              }}
            >
              Từ chối hồ sơ
            </button>
          </div>
        )}
      </div>

      {alertMessage && (
        <div
          role="alert"
          className={`banner banner-${alertMessage.tone}`}
          style={{ marginBottom: '1.5rem' }}
        >
          {alertMessage.text}
        </div>
      )}

      {/* Main Details Grid */}
      <div className="details-grid">
        {/* Status Card */}
        <section className="card" aria-labelledby="status-section-heading">
          <h2 id="status-section-heading" className="card-title">
            Trạng thái xét duyệt
          </h2>
          <dl className="definition-list">
            <div className="definition-item">
              <dt>Trạng thái hiện tại:</dt>
              <dd>
                <span className={`badge ${getStatusBadgeClass(application.status)}`}>
                  {getStatusLabel(application.status)}
                </span>
              </dd>
            </div>
            <div className="definition-item">
              <dt>Mã huấn luyện viên:</dt>
              <dd>
                <code className="code-inline">{application.trainerId}</code>
              </dd>
            </div>
            <div className="definition-item">
              <dt>Ngày nộp:</dt>
              <dd>{application.submittedAt ? new Date(application.submittedAt).toLocaleString('vi-VN') : '—'}</dd>
            </div>
            <div className="definition-item">
              <dt>Ngày xét duyệt:</dt>
              <dd>{application.reviewedAt ? new Date(application.reviewedAt).toLocaleString('vi-VN') : '—'}</dd>
            </div>
            {application.reviewedBy && (
              <div className="definition-item">
                <dt>Người duyệt (Admin ID):</dt>
                <dd>
                  <code className="code-inline">{application.reviewedBy}</code>
                </dd>
              </div>
            )}
          </dl>
        </section>

        {/* Applicant Note Card */}
        <section className="card" aria-labelledby="note-section-heading">
          <h2 id="note-section-heading" className="card-title">
            Ghi chú từ ứng viên
          </h2>
          <div className="content-block">
            {application.applicantNote ? (
              <p className="pre-wrap-text">{application.applicantNote}</p>
            ) : (
              <p className="text-muted">Ứng viên không để lại ghi chú kèm theo.</p>
            )}
          </div>
        </section>

        {/* Review Outcome Card (if decided) */}
        {(application.rejectionReason || application.reviewNotes) && (
          <section className="card" aria-labelledby="review-outcome-heading">
            <h2 id="review-outcome-heading" className="card-title">
              Kết quả đánh giá từ quản trị viên
            </h2>
            <dl className="definition-list">
              {application.rejectionReason && (
                <div className="definition-item">
                  <dt className="text-danger">Lý do từ chối:</dt>
                  <dd className="text-danger font-medium">{application.rejectionReason}</dd>
                </div>
              )}
              {application.reviewNotes && (
                <div className="definition-item">
                  <dt>Ghi chú xét duyệt nội bộ:</dt>
                  <dd>{application.reviewNotes}</dd>
                </div>
              )}
            </dl>
          </section>
        )}

        {/* Attached Credentials */}
        <section className="card" aria-labelledby="credentials-heading">
          <h2 id="credentials-heading" className="card-title">
            Bằng cấp và Chứng chỉ đính kèm
          </h2>
          <div className="content-block">
            <p className="field-hint">
              Số chứng chỉ: {application.certificateIds.length} | Tài liệu đính kèm:{' '}
              {application.documentMediaIds.length}
            </p>
            {application.certificateIds.length > 0 ? (
              <ul className="item-list">
                {application.certificateIds.map((certId) => (
                  <li key={certId}>
                    Mã chứng chỉ: <code className="code-inline">{certId}</code>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted">Không có chứng chỉ đính kèm riêng.</p>
            )}

            {application.documentMediaIds.length > 0 && (
              <div style={{ marginTop: '1rem' }}>
                <h3 className="section-subtitle">Tệp tài liệu (Media IDs đã quét sạch):</h3>
                <ul className="item-list">
                  {application.documentMediaIds.map((mediaId) => (
                    <li key={mediaId}>
                      Tệp media: <code className="code-inline">{mediaId}</code>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </section>
      </div>

      {/* Approve Confirmation Dialog */}
      <ConfirmationDialog
        open={approveOpen}
        title="Phê duyệt hồ sơ huấn luyện viên"
        description="Bạn có chắc chắn muốn phê duyệt hồ sơ này? Quyền huấn luyện (Coaching Authority) sẽ được kích hoạt cho HLV này."
        confirmLabel="Xác nhận phê duyệt"
        cancelLabel="Hủy bỏ"
        busy={isBusy}
        onConfirm={handleApprove}
        onCancel={() => setApproveOpen(false)}
      >
        <div style={{ marginTop: '1rem' }}>
          <label htmlFor="approve-notes" className="field-label">
            Ghi chú xét duyệt (tùy chọn):
          </label>
          <textarea
            id="approve-notes"
            aria-label="Ghi chú xét duyệt"
            value={reviewNotes}
            onChange={(e) => setReviewNotes(e.target.value)}
            placeholder="Nhập ghi chú hoặc kết quả thẩm tra bằng cấp..."
            rows={3}
            className="textarea-input"
            maxLength={2000}
          />
        </div>
      </ConfirmationDialog>

      {/* Reject Confirmation Dialog */}
      <ConfirmationDialog
        open={rejectOpen}
        title="Từ chối hồ sơ huấn luyện viên"
        description="Vui lòng cung cấp lý do từ chối rõ ràng để HLV có thể hiểu nguyên nhân và khắc phục khi nộp lại."
        confirmLabel="Xác nhận từ chối"
        cancelLabel="Hủy bỏ"
        destructive
        confirmDisabled={!rejectionReason.trim()}
        busy={isBusy}
        onConfirm={handleReject}
        onCancel={() => setRejectOpen(false)}
      >
        <div style={{ marginTop: '1rem' }}>
          <label htmlFor="reject-reason" className="field-label">
            Lý do từ chối <span className="text-danger">*</span>:
          </label>
          <textarea
            id="reject-reason"
            aria-label="Lý do từ chối"
            value={rejectionReason}
            onChange={(e) => {
              setRejectionReason(e.target.value)
              if (rejectionError) setRejectionError(null)
            }}
            placeholder="Ví dụ: Bằng cấp đã hết hạn, vui lòng nộp chứng chỉ còn hiệu lực..."
            rows={3}
            className={`textarea-input ${rejectionError ? 'input-error' : ''}`}
            required
            maxLength={2000}
          />
          {rejectionError && <p className="field-error">{rejectionError}</p>}

          <div style={{ marginTop: '0.75rem' }}>
            <label htmlFor="reject-notes" className="field-label">
              Ghi chú nội bộ (tùy chọn):
            </label>
            <textarea
              id="reject-notes"
              aria-label="Ghi chú nội bộ"
              value={reviewNotes}
              onChange={(e) => setReviewNotes(e.target.value)}
              placeholder="Ghi chú nội bộ dành cho đội ngũ admin..."
              rows={2}
              className="textarea-input"
              maxLength={2000}
            />
          </div>
        </div>
      </ConfirmationDialog>
    </div>
  )
}
