import { describe, expect, it } from 'vitest'
import { ApiError } from '../../services/apiClient.ts'
import { getExerciseErrorContent } from './exercisePresentation.ts'

describe('exercise error mapping', () => {
  it.each([
    [new ApiError(401, 'SESSION_REVOKED'), 'Phiên đăng nhập đã hết hạn'],
    [new ApiError(403, 'CATALOG_MANAGE_REQUIRED'), 'Bạn không có quyền truy cập'],
    [new ApiError(404, 'ADMIN_EXERCISE_NOT_FOUND'), 'Không tìm thấy bài tập'],
    [new ApiError(422, 'VALIDATION_FAILED'), 'Không thể tải dữ liệu'],
  ])('maps terminal errors to Vietnamese copy without backend messages', (error, title) => {
    const content = getExerciseErrorContent(error, 'detail')
    expect(content.title).toBe(title)
    expect(content.retryable).toBe(false)
    expect(JSON.stringify(content)).not.toContain(error.errorCode)
  })

  it.each([new ApiError(0, 'NETWORK_ERROR'), new ApiError(503, 'SECRET_BACKEND_MESSAGE')])(
    'marks only network and server errors as retryable',
    (error) => {
      const content = getExerciseErrorContent(error, 'detail')
      expect(content.retryable).toBe(true)
      expect(JSON.stringify(content)).not.toContain(error.errorCode)
    },
  )

  it('uses a stable error code before the HTTP fallback', () => {
    const content = getExerciseErrorContent(
      new ApiError(400, 'ADMIN_EXERCISE_NOT_FOUND'),
      'detail',
    )

    expect(content.title).toBe('Không tìm thấy bài tập')
    expect(content.description).toBe('Bài tập này không tồn tại hoặc hiện không khả dụng.')
  })
})
