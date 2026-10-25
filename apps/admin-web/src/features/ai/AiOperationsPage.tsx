import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'
import { aiRunApi, type AdminAiRunSummary } from '../../services/aiRunApi.ts'
import { ApiError } from '../../services/apiClient.ts'

const PAGE_SIZE = 15

export function AiOperationsPage() {
  const client = useQueryClient()

  // Kill Switch state
  const [toggleDialogOpen, setToggleDialogOpen] = useState(false)
  const [toggleReason, setToggleReason] = useState('')
  const [toggleBusy, setToggleBusy] = useState(false)
  const [alertMessage, setAlertMessage] = useState<{ text: string; tone: 'success' | 'danger' } | null>(null)

  // AI Runs filter & pagination state
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [requestTypeFilter, setRequestTypeFilter] = useState<string>('')
  const [page, setPage] = useState(0)

  // Query feature flag status
  const featureQuery = useQuery({
    queryKey: ['admin-ai-feature-f01'],
    queryFn: aiRunApi.getF01FeatureStatus,
    retry: false,
  })

  // Query AI Runs list
  const runsQuery = useQuery({
    queryKey: ['admin-ai-runs', page, statusFilter, requestTypeFilter],
    queryFn: () =>
      aiRunApi.listRuns({
        page,
        size: PAGE_SIZE,
        status: statusFilter || undefined,
        requestType: requestTypeFilter || undefined,
      }),
    retry: false,
  })

  const handleToggleFeature = async () => {
    if (!featureQuery.data || toggleBusy) return
    const nextState = !featureQuery.data.enabled
    setToggleBusy(true)
    setAlertMessage(null)

    try {
      await aiRunApi.toggleF01Feature({
        enabled: nextState,
        reason: toggleReason.trim() || undefined,
      })
      setAlertMessage({
        text: `Đã ${nextState ? 'BẬT' : 'TẮT'} tính năng F01 thành công. Thay đổi có hiệu lực ngay lập tức và đã ghi vết audit.`,
        tone: 'success',
      })
      await client.invalidateQueries({ queryKey: ['admin-ai-feature-f01'] })
      setToggleDialogOpen(false)
      setToggleReason('')
    } catch (error) {
      if (error instanceof ApiError) {
        setAlertMessage({
          text: `Lỗi khi thay đổi trạng thái tính năng: ${error.errorCode} (HTTP ${error.status})`,
          tone: 'danger',
        })
      } else {
        setAlertMessage({
          text: 'Đã xảy ra lỗi không xác định khi cập nhật tính năng.',
          tone: 'danger',
        })
      }
    } finally {
      setToggleBusy(false)
    }
  }

  const feature = featureQuery.data
  const runsData = runsQuery.data
  const items: AdminAiRunSummary[] = runsData?.items ?? []
  const total = runsData?.total ?? 0

  return (
    <section className="page-stack">
      <header className="page-header">
        <h1>Vận hành AI & Quản lý Kill Switch (F10)</h1>
        <p>
          Kiểm soát tính khả dụng của dịch vụ AI và giám sát nhật ký thực thi (đã che thông tin nhạy cảm).
        </p>
      </header>

      {alertMessage ? (
        <div className={`banner banner--${alertMessage.tone}`} role="alert">
          {alertMessage.text}
        </div>
      ) : null}

      {/* SECTION 1: KILL SWITCH F01 */}
      <div className="surface page-stack">
        <h2>Công tắc khẩn cấp tính năng AI (Kill Switch)</h2>
        {featureQuery.isPending ? (
          <p className="text-muted">Đang tải trạng thái tính năng...</p>
        ) : featureQuery.isError || !feature ? (
          <div className="banner banner--danger">
            Không thể tải trạng thái Kill Switch F01. Vui lòng kiểm tra quyền quản trị.
          </div>
        ) : (
          <div className="feature-control-card">
            <div className="feature-control-info">
              <div className="feature-badge-row">
                <span className="code-badge">{feature.featureCode}</span>
                <span className={`status-badge status-badge--${feature.enabled ? 'published' : 'archived'}`}>
                  {feature.enabled ? 'ĐANG BẬT (ACTIVE)' : 'ĐÃ TẮT (DISABLED)'}
                </span>
              </div>
              <p className="description">{feature.description}</p>
              {feature.updatedAt ? (
                <div className="text-muted small">
                  Cập nhật lần cuối: {new Date(feature.updatedAt).toLocaleString()}
                  {feature.updatedBy ? ` bởi (${feature.updatedBy})` : ''}
                </div>
              ) : null}
              <div className="policy-note small">
                <strong>Chính sách khi Tắt:</strong> Mọi yêu cầu F01 mới bị từ chối ngay lập tức (HTTP 503). Request đang xử lý được lưu vết run nhưng bị chặn giao output. Lịch sử run trước đó vẫn truy cập được.
              </div>
            </div>

            <div className="feature-control-action">
              <button
                className={feature.enabled ? 'button-danger' : 'button-primary'}
                onClick={() => {
                  setToggleDialogOpen(true)
                  setToggleReason('')
                }}
              >
                {feature.enabled ? 'Tắt tính năng F01' : 'Bật tính năng F01'}
              </button>
            </div>
          </div>
        )}
      </div>

      {/* SECTION 2: AI RUN MONITORING TABLE */}
      <div className="surface page-stack">
        <div className="section-header-row">
          <h2>Nhật ký AI Run Telemetry (Đã che dữ liệu nhạy cảm)</h2>
          <div className="privacy-badge">
            <span role="img" aria-label="Privacy Shield">&#128737;</span> Redacted: Không lưu prompt thô hoặc câu hỏi học viên
          </div>
        </div>

        {/* Filters */}
        <div className="filter-bar">
          <label>
            Trạng thái:
            <select
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value)
                setPage(0)
              }}
            >
              <option value="">Tất cả trạng thái</option>
              <option value="SUCCEEDED">SUCCEEDED</option>
              <option value="FAILED">FAILED</option>
              <option value="CANCELLED">CANCELLED</option>
              <option value="RUNNING">RUNNING</option>
              <option value="QUEUED">QUEUED</option>
            </select>
          </label>

          <label>
            Loại yêu cầu:
            <select
              value={requestTypeFilter}
              onChange={(e) => {
                setRequestTypeFilter(e.target.value)
                setPage(0)
              }}
            >
              <option value="">Tất cả loại yêu cầu</option>
              <option value="F01_GENERAL_KNOWLEDGE">F01_GENERAL_KNOWLEDGE</option>
            </select>
          </label>

          <button
            className="button-secondary"
            onClick={() => {
              setStatusFilter('')
              setRequestTypeFilter('')
              setPage(0)
            }}
          >
            Đặt lại bộ lọc
          </button>
        </div>

        {runsQuery.isPending ? (
          <StatePanel title="Đang tải danh sách AI Runs…" />
        ) : runsQuery.isError ? (
          <StatePanel
            title="Không thể tải danh sách AI Runs"
            description="Vui lòng thử lại hoặc kiểm tra quyền truy cập."
            action={
              <button className="button-secondary" onClick={() => void runsQuery.refetch()}>
                Tải lại
              </button>
            }
            tone="danger"
          />
        ) : items.length === 0 ? (
          <StatePanel
            title="Không có bản ghi AI Run nào"
            description="Không tìm thấy bản ghi nào phù hợp với bộ lọc hiện tại."
          />
        ) : (
          <>
            <table className="data-table" aria-label="Bảng nhật ký AI Runs">
              <thead>
                <tr>
                  <th>Run ID</th>
                  <th>Loại yêu cầu / Mục đích</th>
                  <th>Trạng thái</th>
                  <th>Token Usage</th>
                  <th>Độ trễ</th>
                  <th>Lỗi kỹ thuật</th>
                  <th>Thời gian</th>
                  <th>Chi tiết</th>
                </tr>
              </thead>
              <tbody>
                {items.map((run) => (
                  <tr key={run.id}>
                    <td>
                      <code title={run.id}>{run.id ? `${run.id.substring(0, 8)}…` : '—'}</code>
                    </td>
                    <td>
                      <div><strong>{run.requestType}</strong></div>
                      <div className="text-muted small">{run.purpose || '—'}</div>
                    </td>
                    <td>
                      <span className={`status-badge status-badge--${run.status ? run.status.toLowerCase() : 'queued'}`}>
                        {run.status}
                      </span>
                      {run.evaluationOnly ? <span className="badge badge--warning small">Eval</span> : null}
                    </td>
                    <td>
                      {run.totalTokenCount !== null && run.totalTokenCount !== undefined ? (
                        <span>{run.totalTokenCount.toLocaleString()} tokens</span>
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td>{run.latencyMs !== null && run.latencyMs !== undefined ? `${run.latencyMs} ms` : <span className="text-muted">—</span>}</td>
                    <td>
                      {run.errorCode ? (
                        <div>
                          <strong className="text-danger small">{run.errorCode}</strong>
                        </div>
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td>{new Date(run.createdAt).toLocaleString()}</td>
                    <td>
                      <Link to={`/ai/runs/${run.id}`} className="button-link">
                        Xem chi tiết
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* Pagination */}
            <div className="pagination-bar">
              <span className="text-muted">
                Trang {runsData?.page !== undefined ? runsData.page + 1 : 1} / {runsData?.totalPages || 1} (Tổng số: {total} bản ghi)
              </span>
              <div className="button-group">
                <button
                  className="button-secondary"
                  disabled={page === 0}
                  onClick={() => setPage((prev) => Math.max(0, prev - 1))}
                >
                  &larr; Trang trước
                </button>
                <button
                  className="button-secondary"
                  disabled={runsData ? page + 1 >= runsData.totalPages : true}
                  onClick={() => setPage((prev) => prev + 1)}
                >
                  Trang sau &rarr;
                </button>
              </div>
            </div>
          </>
        )}
      </div>

      {/* Confirmation Dialog for Toggle Feature */}
      {toggleDialogOpen && feature ? (
        <ConfirmationDialog
          open={toggleDialogOpen}
          title={feature.enabled ? 'Xác nhận TẮT tính năng F01' : 'Xác nhận BẬT tính năng F01'}
          description={
            feature.enabled
              ? 'Tắt tính năng sẽ lập tức chặn các câu hỏi AI tri thức mới (HTTP 503) và chặn giao output đối với các yêu cầu đang xử lý. Hành động này được ghi audit.'
              : 'Bật tính năng sẽ kích hoạt lại hỏi đáp AI F01 cho học viên và huấn luyện viên. Hành động này được ghi audit.'
          }
          confirmLabel={feature.enabled ? 'Xác nhận Tắt' : 'Xác nhận Bật'}
          destructive={feature.enabled}
          busy={toggleBusy}
          onConfirm={() => void handleToggleFeature()}
          onCancel={() => {
            if (!toggleBusy) {
              setToggleDialogOpen(false)
              setToggleReason('')
            }
          }}
        >
          <div className="form-group">
            <label htmlFor="toggle-reason">Lý do thay đổi trạng thái:</label>
            <input
              id="toggle-reason"
              type="text"
              value={toggleReason}
              onChange={(e) => setToggleReason(e.target.value)}
              placeholder="Nhập lý do vận hành hoặc bảo trì..."
              disabled={toggleBusy}
            />
          </div>
        </ConfirmationDialog>
      ) : null}
    </section>
  )
}
