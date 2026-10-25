import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { StatePanel } from '../../components/StatePanel.tsx'
import { knowledgeApi } from '../../services/knowledgeApi.ts'

export function KnowledgeListPage() {
  const query = useQuery({
    queryKey: ['admin-knowledge-documents'],
    queryFn: () => knowledgeApi.listDocuments(0, 50),
    retry: false,
  })

  if (query.isPending) {
    return <StatePanel title="Đang tải danh sách tài liệu tri thức…" />
  }

  if (query.isError) {
    return (
      <StatePanel
        title="Không thể tải danh sách tài liệu tri thức"
        description="Vui lòng thử lại hoặc kiểm tra quyền truy cập."
        action={
          <button className="button-secondary" onClick={() => void query.refetch()}>
            Tải lại
          </button>
        }
        tone="danger"
      />
    )
  }

  const documents = query.data ?? []

  return (
    <section className="page-stack">
      <header className="page-header">
        <h1>Quản lý cơ sở tri thức (Knowledge Base)</h1>
        <p>
          Xem danh mục tài liệu nguồn, lịch sử phiên bản và trạng thái kiểm định sẵn sàng cho AI Retrieval (F09).
        </p>
      </header>

      {documents.length === 0 ? (
        <StatePanel
          title="Chưa có tài liệu tri thức nào"
          description="Hệ thống chưa có tài liệu tri thức nào được import vào cơ sở dữ liệu."
        />
      ) : (
        <div className="surface">
          <table className="data-table" aria-label="Danh sách tài liệu tri thức">
            <thead>
              <tr>
                <th>Mã tài liệu</th>
                <th>Tiêu đề</th>
                <th>Nguồn</th>
                <th>Loại tài liệu</th>
                <th>Ngôn ngữ</th>
                <th>Ngày tạo</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {documents.map((doc) => (
                <tr key={doc.id}>
                  <td>
                    <code>{doc.documentCode}</code>
                  </td>
                  <td>
                    <strong>{doc.title}</strong>
                    {doc.author ? <div className="text-muted">Tác giả: {doc.author}</div> : null}
                  </td>
                  <td>
                    {doc.sourceUrl ? (
                      <a href={doc.sourceUrl} target="_blank" rel="noreferrer">
                        {doc.sourceName || 'Liên kết nguồn'}
                      </a>
                    ) : (
                      doc.sourceName || '—'
                    )}
                  </td>
                  <td>{doc.documentType || '—'}</td>
                  <td>{doc.language || '—'}</td>
                  <td>{new Date(doc.createdAt).toLocaleDateString()}</td>
                  <td>
                    <Link
                      to={`/knowledge/${doc.id}`}
                      className="button-link"
                      aria-label={`Xem chi tiết ${doc.documentCode}`}
                    >
                      Chi tiết & Phiên bản
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
