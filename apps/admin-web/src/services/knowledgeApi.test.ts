import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './apiClient.ts'
import { knowledgeApi } from './knowledgeApi.ts'

vi.mock('./apiClient.ts', () => ({ apiRequest: vi.fn() }))

describe('knowledgeApi transport', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists documents with page and size', async () => {
    await knowledgeApi.listDocuments(1, 20)
    expect(apiRequest).toHaveBeenCalledWith('/admin/content/knowledge/documents?page=1&size=20')
  })

  it('gets document by id', async () => {
    await knowledgeApi.getDocument('doc-123')
    expect(apiRequest).toHaveBeenCalledWith('/admin/content/knowledge/documents/doc-123')
  })

  it('lists versions for a document', async () => {
    await knowledgeApi.listVersions('doc-123')
    expect(apiRequest).toHaveBeenCalledWith('/admin/content/knowledge/documents/doc-123/versions')
  })

  it('reviews version with decision and notes', async () => {
    await knowledgeApi.reviewVersion('ver-456', { decision: 'APPROVED', notes: 'Looks solid' })
    expect(apiRequest).toHaveBeenCalledWith('/admin/content/knowledge/versions/ver-456/review', {
      method: 'POST',
      body: JSON.stringify({ decision: 'APPROVED', notes: 'Looks solid' }),
    })
  })

  it('publishes version', async () => {
    await knowledgeApi.publishVersion('ver-456')
    expect(apiRequest).toHaveBeenCalledWith('/admin/content/knowledge/versions/ver-456/publish', {
      method: 'POST',
    })
  })

  it('archives version with optional reason', async () => {
    await knowledgeApi.archiveVersion('ver-456', { reason: 'Superseded' })
    expect(apiRequest).toHaveBeenCalledWith('/admin/content/knowledge/versions/ver-456/archive', {
      method: 'POST',
      body: JSON.stringify({ reason: 'Superseded' }),
    })
  })
})
