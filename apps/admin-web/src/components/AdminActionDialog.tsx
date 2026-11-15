import { useState, useId, useEffect, useRef } from 'react'

export interface AdminActionDialogProps {
  open: boolean
  title: string
  targetName: string
  actionLabel: string
  impactWarning: string
  requiredPermission?: string
  destructive?: boolean
  busy?: boolean
  errorMessage?: string | null
  onConfirm: (reason: string, password: string) => void
  onCancel: () => void
}

export function AdminActionDialog(props: AdminActionDialogProps) {
  if (!props.open) return null
  return <AdminActionDialogModal {...props} />
}

function AdminActionDialogModal({
  title,
  targetName,
  actionLabel,
  impactWarning,
  requiredPermission,
  destructive = false,
  busy = false,
  errorMessage = null,
  onConfirm,
  onCancel,
}: Omit<AdminActionDialogProps, 'open'>) {
  const [reason, setReason] = useState('')
  const [password, setPassword] = useState('')
  const dialogRef = useRef<HTMLDivElement>(null)
  const reasonInputRef = useRef<HTMLTextAreaElement>(null)
  const generatedId = useId()
  const titleId = `action-dialog-title-${generatedId}`
  const descId = `action-dialog-desc-${generatedId}`

  useEffect(() => {
    const timer = setTimeout(() => reasonInputRef.current?.focus(), 50)
    return () => clearTimeout(timer)
  }, [])

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) {
        e.preventDefault()
        onCancel()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [busy, onCancel])

  const isReasonValid = reason.trim().length >= 5
  const isPasswordValid = password.trim().length > 0
  const canSubmit = isReasonValid && isPasswordValid && !busy

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (canSubmit) {
      onConfirm(reason.trim(), password.trim())
    }
  }

  return (
    <div
      className="dialog-backdrop"
      role="presentation"
      onMouseDown={(e) => {
        if (!busy && e.target === e.currentTarget) onCancel()
      }}
    >
      <div
        ref={dialogRef}
        className="confirmation-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        style={{ maxWidth: '540px' }}
      >
        <form onSubmit={handleSubmit}>
          <div className="dialog-header">
            <h2 id={titleId} className="dialog-title">
              {title}
            </h2>
            <p id={descId} className="dialog-target">
              Đối tượng: <strong>{targetName}</strong>
            </p>
          </div>

          <div
            className={`dialog-warning ${destructive ? 'warning-danger' : 'warning-caution'}`}
            role="alert"
          >
            <strong>Cảnh báo tác động:</strong> {impactWarning}
          </div>

          {requiredPermission ? (
            <p className="field-hint" style={{ marginTop: '0.5rem' }}>
              Thao tác này yêu cầu quyền: <code>{requiredPermission}</code> và sẽ được ghi vào nhật ký kiểm toán (Audit Log) bất biến.
            </p>
          ) : null}

          {errorMessage ? (
            <div className="state-panel error-inline" style={{ margin: '0.75rem 0', padding: '0.75rem', background: 'var(--color-bg-danger-subtle, #fde8e8)', border: '1px solid var(--color-border-danger, #f8b4b4)', borderRadius: '4px', color: 'var(--color-text-danger, #9b1c1c)' }}>
              {errorMessage}
            </div>
          ) : null}

          <div className="form-group" style={{ marginTop: '1rem' }}>
            <label htmlFor={`reason-${generatedId}`} className="field-label">
              Lý do thực hiện <span style={{ color: 'red' }}>*</span> (tối thiểu 5 ký tự):
            </label>
            <textarea
              id={`reason-${generatedId}`}
              ref={reasonInputRef}
              className="text-input"
              rows={3}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Nhập lý do chi tiết cho hành động này..."
              disabled={busy}
              required
              minLength={5}
              maxLength={2000}
            />
          </div>

          <div className="form-group" style={{ marginTop: '1rem' }}>
            <label htmlFor={`stepup-password-${generatedId}`} className="field-label">
              Mật khẩu quản trị viên (Xác thực bảo vệ Step-Up) <span style={{ color: 'red' }}>*</span>:
            </label>
            <input
              id={`stepup-password-${generatedId}`}
              type="password"
              className="text-input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Nhập mật khẩu tài khoản của bạn để xác thực..."
              disabled={busy}
              required
            />
          </div>

          <div className="dialog-actions" style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', marginTop: '1.5rem' }}>
            <button
              type="button"
              className="button-secondary"
              onClick={onCancel}
              disabled={busy}
            >
              Hủy bỏ
            </button>
            <button
              type="submit"
              className={destructive ? 'button-danger' : 'button-primary'}
              disabled={!canSubmit}
            >
              {busy ? 'Đang xử lý…' : actionLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
