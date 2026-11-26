import { useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { StatePanel } from '../../components/StatePanel.tsx'
import { ApiError } from '../../services/apiClient.ts'
import { knowledgeApi } from '../../services/knowledgeApi.ts'
import { KnowledgeImportPanel } from './KnowledgeImportPanel.tsx'

const PAGE_SIZE = 50

function listErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
    if (error.status === 403) return 'Bạn không có quyền xem cơ sở tri thức.'
    if (error.status === 409) return 'Danh sách đã thay đổi. Tải lại để xem dữ liệu mới.'
    if (error.status === 0 || error.status === 504 || error.status === 408) return 'Mất kết nối hoặc quá thời gian chờ. Thử tải lại danh sách.'
  }
  return 'Vui lòng thử lại hoặc kiểm tra quyền truy cập.'
}

export function KnowledgeListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const pageParam = Number(searchParams.get('page') ?? '0')
  const page = Number.isSafeInteger(pageParam) && pageParam >= 0 ? pageParam : 0
  const query = useQuery({
    queryKey: ['admin-knowledge-documents', page, PAGE_SIZE],
    queryFn: () => knowledgeApi.listDocuments(page, PAGE_SIZE),
    retry: false,
  })
  const documents = query.data ?? []
  const accessError = query.error instanceof ApiError && (query.error.status === 401 || query.error.status === 403)
  const changePage = (nextPage: number) => setSearchParams(nextPage === 0 ? {} : { page: String(nextPage) })

  return (
    <section className="page-stack">
      <header className="page-header">
        <h1>Quản lý cơ sở tri thức (Knowledge Base)</h1>
        <p>
          Xem danh mục tài liệu nguồn, lịch sử phiên bản và trạng thái kiểm định sẵn sàng cho AI Retrieval (F09).
        </p>
      </header>

      {!accessError ? <KnowledgeImportPanel /> : null}

      {query.isPending ? <StatePanel title="Đang tải danh sách tài liệu tri thức…" /> : null}
      {query.isError ? <StatePanel title="Không thể tải danh sách tài liệu tri thức"
        description={listErrorMessage(query.error)} tone="danger"
        action={<button className="button-secondary" onClick={() => void query.refetch()}>Tải lại</button>} /> : null}
      {query.isSuccess && documents.length === 0 ? (
        <StatePanel
          title={page === 0 ? 'Chưa có tài liệu tri thức nào' : 'Trang này không có tài liệu tri thức'}
          description={page === 0 ? 'Hệ thống chưa có tài liệu tri thức nào được import vào cơ sở dữ liệu.' : 'Quay lại trang trước để xem tài liệu.'}
        />
      ) : null}
      {query.isSuccess && documents.length > 0 ? (
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
                      to={`/knowledge/${doc.id}?page=${page}`}
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
      ) : null}
      {query.isSuccess && (page > 0 || documents.length === PAGE_SIZE) ? <nav className="pagination" aria-label="Phân trang tri thức">
        <button type="button" className="button-secondary" disabled={page === 0} onClick={() => changePage(page - 1)}>Trang trước</button>
        <span>Trang {page + 1}</span>
        <button type="button" className="button-secondary" disabled={documents.length < PAGE_SIZE}
          onClick={() => changePage(page + 1)}>Trang kế</button>
      </nav> : null}
    </section>
  )
}
