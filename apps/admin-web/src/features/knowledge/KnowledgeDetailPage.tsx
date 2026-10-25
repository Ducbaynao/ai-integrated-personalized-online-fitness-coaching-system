import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'
import { knowledgeApi, type KnowledgeVersion } from '../../services/knowledgeApi.ts'
import { ApiError } from '../../services/apiClient.ts'

type VersionAction =
  | { kind: 'review'; version: KnowledgeVersion; decision: 'APPROVED' | 'REJECTED' }
  | { kind: 'publish'; version: KnowledgeVersion }
  | { kind: 'archive'; version: KnowledgeVersion }

export function KnowledgeDetailPage() {
  const { documentId } = useParams<{ documentId: string }>()
  const client = useQueryClient()

  const [activeAction, setActiveAction] = useState<VersionAction | null>(null)
  const [actionReason, setActionReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [alertMessage, setAlertMessage] = useState<{ text: string; tone: 'success' | 'danger' } | null>(null)

  const docQuery = useQuery({
    queryKey: ['admin-knowledge-document', documentId],
    queryFn: () => knowledgeApi.getDocument(documentId!),
    enabled: Boolean(documentId),
    retry: false,
  })

  const versionsQuery = useQuery({
    queryKey: ['admin-knowledge-versions', documentId],
    queryFn: () => knowledgeApi.listVersions(documentId!),
    enabled: Boolean(documentId),
    retry: false,
  })

  if (docQuery.isPending || versionsQuery.isPending) {
    return <StatePanel title="Đang tải thông tin tài liệu và phiên bản…" />
  }

  if (docQuery.isError || !docQuery.data) {
    return (
      <StatePanel
        title="Không tìm thấy tài liệu tri thức"
        description="Tài liệu không tồn tại hoặc bạn không có quyền xem."
        action={
          <Link to="/knowledge" className="button-secondary">
            Quay lại danh sách
          </Link>
        }
        tone="danger"
      />
    )
  }

  const doc = docQuery.data
  const versions = versionsQuery.data ?? []

  const handleConfirmAction = async () => {
    if (!activeAction || busy) return
    setBusy(true)
    setAlertMessage(null)

    try {
      if (activeAction.kind === 'review') {
        await knowledgeApi.reviewVersion(activeAction.version.id, {
          decision: activeAction.decision,
          notes: actionReason.trim() || undefined,
        })
        setAlertMessage({
          text: `Đã ${activeAction.decision === 'APPROVED' ? 'chấp thuận (APPROVE)' : 'từ chối (REJECT)'} phiên bản ${activeAction.version.versionLabel}.`,
          tone: 'success',
        })
      } else if (activeAction.kind === 'publish') {
        await knowledgeApi.publishVersion(activeAction.version.id)
        setAlertMessage({
          text: `Đã công bố (PUBLISH) phiên bản ${activeAction.version.versionLabel} thành công vào Knowledge Base sẵn sàng phục vụ AI.`,
          tone: 'success',
        })
      } else if (activeAction.kind === 'archive') {
        await knowledgeApi.archiveVersion(activeAction.version.id, {
          reason: actionReason.trim() || undefined,
        })
        setAlertMessage({
          text: `Đã lưu trữ (ARCHIVE) phiên bản ${activeAction.version.versionLabel}.`,
          tone: 'success',
        })
      }

      await client.invalidateQueries({ queryKey: ['admin-knowledge-versions', documentId] })
      setActiveAction(null)
      setActionReason('')
    } catch (error) {
      if (error instanceof ApiError) {
        setAlertMessage({
          text: `Lỗi thao tác: ${error.errorCode} (HTTP ${error.status})`,
          tone: 'danger',
        })
      } else {
        setAlertMessage({
          text: 'Đã xảy ra lỗi không xác định khi thực hiện thao tác.',
          tone: 'danger',
        })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="page-stack">
      <header className="page-header">
        <Link to="/knowledge" className="back-link">
          &larr; Danh sách tài liệu
        </Link>
        <h1>{doc.title}</h1>
        <p>Mã tài liệu: <code>{doc.documentCode}</code></p>
      </header>

      {alertMessage ? (
        <div className={`banner banner--${alertMessage.tone}`} role="alert">
          {alertMessage.text}
        </div>
      ) : null}

      <div className="surface page-stack">
        <h2>Thông tin tài liệu nguồn</h2>
        <dl className="property-list">
          <div>
            <dt>Nguồn:</dt>
            <dd>
              {doc.sourceUrl ? (
                <a href={doc.sourceUrl} target="_blank" rel="noreferrer">
                  {doc.sourceName || doc.sourceUrl}
                </a>
              ) : (
                doc.sourceName || '—'
              )}
            </dd>
          </div>
          <div>
            <dt>Tác giả / Tổ chức:</dt>
            <dd>{doc.author || '—'}</dd>
          </div>
          <div>
            <dt>Loại tài liệu:</dt>
            <dd>{doc.documentType || '—'}</dd>
          </div>
          <div>
            <dt>Ngôn ngữ:</dt>
            <dd>{doc.language || '—'}</dd>
          </div>
          <div>
            <dt>Ngày ghi nhận:</dt>
            <dd>{new Date(doc.createdAt).toLocaleString()}</dd>
          </div>
        </dl>
      </div>

      <div className="surface page-stack">
        <h2>Lịch sử các phiên bản ({versions.length})</h2>
        {versions.length === 0 ? (
          <p className="text-muted">Chưa có phiên bản nào cho tài liệu này.</p>
        ) : (
          <table className="data-table" aria-label="Danh sách phiên bản tài liệu">
            <thead>
              <tr>
                <th>Phiên bản</th>
                <th>Trạng thái</th>
                <th>Kỹ thuật</th>
                <th>Dung lượng</th>
                <th>Content Hash</th>
                <th>Ngày tạo</th>
                <th>Thao tác quản trị</th>
              </tr>
            </thead>
            <tbody>
              {versions.map((ver) => {
                const canReview = ver.status === 'PENDING_REVIEW' || ver.status === 'DRAFT'
                const canPublish = ver.status === 'APPROVED' && ver.technicalReadiness === 'READY'
                const canArchive = ver.status === 'PUBLISHED'

                return (
                  <tr key={ver.id}>
                    <td>
                      <strong>{ver.versionLabel}</strong> (v{ver.versionNumber})
                    </td>
                    <td>
                      <span className={`status-badge status-badge--${ver.status.toLowerCase()}`}>
                        {ver.status}
                      </span>
                    </td>
                    <td>
                      {ver.technicalReadiness ? (
                        <span className={`status-badge status-badge--${ver.technicalReadiness.toLowerCase()}`}>
                          {ver.technicalReadiness}
                        </span>
                      ) : (
                        '—'
                      )}
                      {ver.holdReason ? (
                        <div className="text-danger small">Lý do: {ver.holdReason}</div>
                      ) : null}
                    </td>
                    <td>{ver.sourceBytes ? `${Math.round(ver.sourceBytes / 1024)} KB` : '—'}</td>
                    <td>
                      <code title={ver.contentHash}>
                        {ver.contentHash ? ver.contentHash.substring(0, 10) + '…' : '—'}
                      </code>
                    </td>
                    <td>{new Date(ver.createdAt).toLocaleDateString()}</td>
                    <td>
                      <div className="button-group">
                        {canReview ? (
                          <>
                            <button
                              className="button-small"
                              onClick={() => {
                                setActiveAction({ kind: 'review', version: ver, decision: 'APPROVED' })
                                setActionReason('')
                              }}
                            >
                              Duyệt (Approve)
                            </button>
                            <button
                              className="button-small button-danger"
                              onClick={() => {
                                setActiveAction({ kind: 'review', version: ver, decision: 'REJECTED' })
                                setActionReason('')
                              }}
                            >
                              Từ chối
                            </button>
                          </>
                        ) : null}

                        {canPublish ? (
                          <button
                            className="button-small button-primary"
                            onClick={() => {
                              setActiveAction({ kind: 'publish', version: ver })
                              setActionReason('')
                            }}
                          >
                            Công bố (Publish)
                          </button>
                        ) : null}

                        {canArchive ? (
                          <button
                            className="button-small button-secondary"
                            onClick={() => {
                              setActiveAction({ kind: 'archive', version: ver })
                              setActionReason('')
                            }}
                          >
                            Lưu trữ (Archive)
                          </button>
                        ) : null}

                        {!canReview && !canPublish && !canArchive ? (
                          <span className="text-muted small">Không khả dụng</span>
                        ) : null}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {activeAction ? (
        <ConfirmationDialog
          open={Boolean(activeAction)}
          title={
            activeAction.kind === 'review'
              ? activeAction.decision === 'APPROVED'
                ? `Chấp thuận phiên bản ${activeAction.version.versionLabel}`
                : `Từ chối phiên bản ${activeAction.version.versionLabel}`
              : activeAction.kind === 'publish'
                ? `Công bố phiên bản ${activeAction.version.versionLabel}`
                : `Lưu trữ phiên bản ${activeAction.version.versionLabel}`
          }
          description={
            activeAction.kind === 'review'
              ? 'Xác nhận kết quả thẩm định nội dung tri thức. Kết quả sẽ được ghi vết audit.'
              : activeAction.kind === 'publish'
                ? 'Công bố phiên bản này vào chỉ mục tri thức AI. Hệ thống RAG sẽ kích hoạt truy xuất từ phiên bản này.'
                : 'Lưu trữ phiên bản này. Phiên bản sẽ không còn được sử dụng để trích xuất tri thức mới.'
          }
          confirmLabel={
            activeAction.kind === 'review'
              ? activeAction.decision === 'APPROVED' ? 'Xác nhận Duyệt' : 'Xác nhận Từ chối'
              : activeAction.kind === 'publish' ? 'Công bố ngay' : 'Xác nhận Lưu trữ'
          }
          destructive={activeAction.kind === 'archive' || (activeAction.kind === 'review' && activeAction.decision === 'REJECTED')}
          busy={busy}
          onConfirm={() => void handleConfirmAction()}
          onCancel={() => {
            if (!busy) {
              setActiveAction(null)
              setActionReason('')
            }
          }}
        >
          {activeAction.kind !== 'publish' ? (
            <div className="form-group">
              <label htmlFor="action-reason">Ghi chú / Lý do quyết định:</label>
              <textarea
                id="action-reason"
                rows={3}
                value={actionReason}
                onChange={(e) => setActionReason(e.target.value)}
                placeholder="Nhập lý do thẩm định hoặc lý do lưu trữ..."
                disabled={busy}
              />
            </div>
          ) : null}
        </ConfirmationDialog>
      ) : null}
    </section>
  )
}
