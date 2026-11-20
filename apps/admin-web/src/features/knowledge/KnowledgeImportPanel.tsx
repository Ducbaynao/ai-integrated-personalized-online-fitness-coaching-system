import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../services/apiClient.ts'
import { knowledgeApi, type ImportCorpusBundleRequest } from '../../services/knowledgeApi.ts'
import { useAuth } from '../auth/authContextValue.ts'

function parseBundle(text: string): ImportCorpusBundleRequest {
  const value: unknown = JSON.parse(text)
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      !('items' in value) || !Array.isArray(value.items) || value.items.length === 0 ||
      !value.items.every((item: unknown) => item && typeof item === 'object' &&
        ['documentCode', 'sourceId', 'title', 'versionLabel', 'contentHash', 'content'].every((field) =>
          typeof Reflect.get(item, field) === 'string' && (Reflect.get(item, field) as string).trim().length > 0))) {
    throw new Error('Bundle cần items không rỗng; mỗi mục cần mã, nguồn, tiêu đề, phiên bản, SHA-256 và nội dung.')
  }
  return value as ImportCorpusBundleRequest
}

function importErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Đăng nhập lại trước khi import.'
    if (error.status === 403) return 'Bạn không còn quyền KNOWLEDGE_IMPORT.'
    if (error.status === 409) return 'Phiên bản hoặc thông tin nguồn xung đột. Kiểm tra lại bundle và danh sách trước khi gửi lại.'
    if (error.status === 0 || error.status === 504 || error.status === 408) {
      return 'Chưa rõ kết quả import do mất kết nối hoặc quá thời gian chờ. Kiểm tra danh sách trước khi tự gửi lại; hệ thống không tự thử lại.'
    }
    if (error.status === 400) return `Bundle không hợp lệ (${error.errorCode}). Kiểm tra dữ liệu và SHA-256.`
    return `Import thất bại: ${error.errorCode} (HTTP ${error.status}).`
  }
  return 'Không thể import bundle. Kiểm tra kết nối và trạng thái tài liệu trước khi thử lại.'
}

export function KnowledgeImportPanel() {
  const auth = useAuth()
  const client = useQueryClient()
  const [bundleText, setBundleText] = useState('')
  const [validationError, setValidationError] = useState('')
  const [accessDenied, setAccessDenied] = useState(false)
  const canImport = auth.status === 'authenticated' && auth.user?.roles.includes('ADMIN') &&
    auth.user.permissions.includes('KNOWLEDGE_IMPORT')
  const mutation = useMutation({
    mutationFn: knowledgeApi.importCorpus,
    retry: false,
    onSuccess: () => { void client.invalidateQueries({ queryKey: ['admin-knowledge-documents'] }) },
    onError: (error) => { if (error instanceof ApiError && error.status === 403) setAccessDenied(true) },
  })

  if (!canImport) return null
  if (accessDenied) return <div className="banner banner--danger" role="alert">Bạn không còn quyền KNOWLEDGE_IMPORT. Đã dừng thao tác import.</div>

  const submit = () => {
    if (mutation.isPending) return
    setValidationError('')
    mutation.reset()
    try {
      mutation.mutate(parseBundle(bundleText))
    } catch (error) {
      setValidationError(error instanceof SyntaxError ? 'JSON không hợp lệ.' : (error as Error).message)
    }
  }

  return <section className="surface page-stack" aria-label="Import tri thức">
    <h2>Import bundle tri thức</h2>
    <p>Nhập JSON theo ImportCorpusBundleRequest. Nội dung được kiểm tra và tạo phiên bản DRAFT; cần review và publish riêng.</p>
    <div className="form-group">
      <label htmlFor="knowledge-import-json">Bundle JSON</label>
      <textarea id="knowledge-import-json" rows={8} value={bundleText}
        onChange={(event) => { setBundleText(event.target.value); mutation.reset(); setValidationError('') }} disabled={mutation.isPending}
        placeholder='{"items":[{"documentCode":"...","sourceId":"...","title":"...","versionLabel":"...","contentHash":"...","content":"..."}]}' />
    </div>
    <button className="button-primary" type="button" onClick={submit} disabled={mutation.isPending || !bundleText.trim()}>
      {mutation.isPending ? 'Đang import…' : 'Import bundle'}
    </button>
    {validationError ? <div className="banner banner--danger" role="alert">{validationError}</div> : null}
    {mutation.isError ? <div className="banner banner--danger" role="alert">{importErrorMessage(mutation.error)}</div> : null}
    {mutation.data ? <div className="page-stack" role="status">
      <strong>Kết quả import: {mutation.data.importedCount} imported, {mutation.data.skippedCount} skipped / {mutation.data.totalItems} mục.</strong>
      {mutation.data.importedItems.length > 0 ? <div>
        <h3>Đã import (DRAFT)</h3>
        <ul>{mutation.data.importedItems.map((item) => <li key={item.versionId}>
          {item.documentCode} · {item.versionLabel} · {item.status} · {item.technicalReadiness}
        </li>)}</ul>
      </div> : null}
      {mutation.data.skippedItems.length > 0 ? <div>
        <h3>Đã bỏ qua</h3>
        <ul>{mutation.data.skippedItems.map((item) => <li key={item}>{item}</li>)}</ul>
      </div> : null}
    </div> : null}
  </section>
}
