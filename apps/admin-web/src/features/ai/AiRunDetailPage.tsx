import { useParams, Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { aiRunApi } from '../../services/aiRunApi.ts'

export function AiRunDetailPage() {
  const { runId } = useParams<{ runId: string }>()

  const query = useQuery({
    queryKey: ['admin-ai-run-detail', runId],
    queryFn: () => aiRunApi.getRunDetail(runId!),
    enabled: Boolean(runId),
    retry: false,
  })

  if (query.isPending) {
    return <StatePanel title="Đang tải chi tiết AI Run…" />
  }

  if (query.isError || !query.data) {
    return (
      <StatePanel
        title="Không tìm thấy bản ghi AI Run"
        description="Bản ghi không tồn tại hoặc bạn không có quyền truy cập."
        action={
          <Link to="/ai/operations" className="button-secondary">
            Quay lại trang Vận hành AI
          </Link>
        }
        tone="danger"
      />
    )
  }

  const run = query.data

  return (
    <section className="page-stack">
      <header className="page-header">
        <Link to="/ai/operations" className="back-link">
          &larr; Quay lại danh sách AI Runs
        </Link>
        <h1>Chi tiết AI Run Telemetry</h1>
        <p>Run ID: <code>{run.id}</code></p>
      </header>

      {/* Security Privacy Notice */}
      <div className="banner banner--neutral">
        <strong>Chính sách bảo vệ quyền riêng tư:</strong> Toàn bộ dữ liệu câu hỏi, câu trả lời, prompt thô và thông tin nhận dạng cá nhân của người dùng đã được che giấu (redacted) ngay tại server. Chỉ thông tin telemetry vận hành kỹ thuật được phép hiển thị.
      </div>

      {/* Section 1: Run Summary */}
      <div className="surface page-stack">
        <h2>Thông tin thực thi</h2>
        <dl className="property-list">
          <div>
            <dt>Trạng thái thực thi:</dt>
            <dd>
              <span className={`status-badge status-badge--${run.status ? run.status.toLowerCase() : 'queued'}`}>
                {run.status}
              </span>
            </dd>
          </div>
          <div>
            <dt>Loại yêu cầu:</dt>
            <dd><code>{run.requestType}</code></dd>
          </div>
          <div>
            <dt>Mục đích (Purpose):</dt>
            <dd>{run.purpose || '—'}</dd>
          </div>
          <div>
            <dt>Correlation ID:</dt>
            <dd><code>{run.correlationId || '—'}</code></dd>
          </div>
          <div>
            <dt>Chế độ chỉ đánh giá (Evaluation Only):</dt>
            <dd>{run.evaluationOnly ? 'CÓ (EVALUATION)' : 'KHÔNG (PRODUCTION)'}</dd>
          </div>
          <div>
            <dt>Thời gian tạo (Queued):</dt>
            <dd>{new Date(run.createdAt).toLocaleString()}</dd>
          </div>
          <div>
            <dt>Thời gian bắt đầu:</dt>
            <dd>{run.startedAt ? new Date(run.startedAt).toLocaleString() : '—'}</dd>
          </div>
          <div>
            <dt>Thời gian kết thúc:</dt>
            <dd>{run.completedAt ? new Date(run.completedAt).toLocaleString() : '—'}</dd>
          </div>
        </dl>
      </div>

      {/* Section 2: Technical Telemetry */}
      <div className="surface page-stack">
        <h2>Telemetry kỹ thuật & Token Usage</h2>
        <dl className="property-list">
          <div>
            <dt>Độ trễ tổng thể (Latency):</dt>
            <dd>{run.latencyMs !== null && run.latencyMs !== undefined ? `${run.latencyMs} ms` : '—'}</dd>
          </div>
          <div>
            <dt>Token Usage:</dt>
            <dd>
              {run.totalTokenCount !== null && run.totalTokenCount !== undefined ? (
                <span>
                  Tổng: <strong>{run.totalTokenCount.toLocaleString()}</strong> (Prompt: {run.inputTokenCount?.toLocaleString() ?? '—'}, Completion: {run.outputTokenCount?.toLocaleString() ?? '—'})
                </span>
              ) : (
                '— (Không có dữ liệu token)'
              )}
            </dd>
          </div>
        </dl>

        {run.errorCode || run.errorMessage ? (
          <div className="banner banner--danger">
            <div><strong>Mã lỗi:</strong> <code>{run.errorCode || 'UNKNOWN_ERROR'}</code></div>
            {run.errorMessage ? <div><strong>Thông điệp lỗi:</strong> {run.errorMessage}</div> : null}
          </div>
        ) : null}
      </div>

      {/* Section 3: Safety Validations */}
      <div className="surface page-stack">
        <h2>Kết quả kiểm định an toàn ({run.validations?.length || 0})</h2>
        {!run.validations || run.validations.length === 0 ? (
          <p className="text-muted">Không có bản ghi kiểm định an toàn riêng lẻ cho run này.</p>
        ) : (
          <table className="data-table" aria-label="Bảng kiểm định an toàn">
            <thead>
              <tr>
                <th>Bộ kiểm tra (Validator)</th>
                <th>Kết quả</th>
                <th>Mức độ (Severity)</th>
                <th>Chi tiết kỹ thuật</th>
                <th>Thời điểm</th>
              </tr>
            </thead>
            <tbody>
              {run.validations.map((v, idx) => (
                <tr key={idx}>
                  <td><code>{v.validatorCode}</code></td>
                  <td>
                    <span className={`status-badge status-badge--${v.passed ? 'published' : 'archived'}`}>
                      {v.passed ? 'PASSED' : 'FAILED'}
                    </span>
                  </td>
                  <td>{v.severity}</td>
                  <td>{v.details || '—'}</td>
                  <td>{new Date(v.createdAt).toLocaleTimeString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Section 4: Document Citations */}
      <div className="surface page-stack">
        <h2>Nguồn trích dẫn tài liệu ({run.citations?.length || 0})</h2>
        {!run.citations || run.citations.length === 0 ? (
          <p className="text-muted">Không có trích dẫn tài liệu cho run này.</p>
        ) : (
          <table className="data-table" aria-label="Bảng trích dẫn tài liệu">
            <thead>
              <tr>
                <th>Chunk ID</th>
                <th>Thứ tự xếp hạng (Rank)</th>
                <th>Điểm tương đồng (Similarity Score)</th>
              </tr>
            </thead>
            <tbody>
              {run.citations.map((c, idx) => (
                <tr key={idx}>
                  <td><code>{c.chunkId}</code></td>
                  <td>#{c.rank}</td>
                  <td>{c.similarityScore !== null && c.similarityScore !== undefined ? c.similarityScore : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  )
}
