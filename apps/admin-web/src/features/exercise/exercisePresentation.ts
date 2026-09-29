import { ApiError } from '../../services/apiClient.ts'
import type { ExerciseDifficulty, ExerciseLifecycleStatus } from '../../types/exercise.ts'

export const lifecycleLabels: Record<ExerciseLifecycleStatus, string> = {
  DRAFT: 'Bản nháp',
  ACTIVE: 'Đang hoạt động',
  ARCHIVED: 'Đã lưu trữ',
}

export const difficultyLabels: Record<ExerciseDifficulty, string> = {
  BEGINNER: 'Cơ bản',
  INTERMEDIATE: 'Trung cấp',
  ADVANCED: 'Nâng cao',
}

export function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat('vi-VN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export interface ExerciseErrorContent {
  title: string
  description: string
  retryable: boolean
}

export function getExerciseErrorContent(
  error: unknown,
  context: 'list' | 'detail',
): ExerciseErrorContent {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return {
        title: 'Không thể kết nối đến hệ thống',
        description: 'Vui lòng kiểm tra kết nối mạng rồi thử lại.',
        retryable: true,
      }
    }
    if (error.status >= 500) {
      return {
        title: 'Hệ thống đang gặp sự cố',
        description: 'Dữ liệu chưa thể tải. Vui lòng thử lại sau.',
        retryable: true,
      }
    }
    if (error.status === 403) {
      return {
        title: 'Bạn không có quyền truy cập',
        description: 'Quyền quản lý danh mục hiện không khả dụng.',
        retryable: false,
      }
    }
    if (error.status === 404 && context === 'detail') {
      return {
        title: 'Không tìm thấy bài tập',
        description: 'Bài tập này không tồn tại hoặc hiện không khả dụng.',
        retryable: false,
      }
    }
    if (error.status === 401) {
      return {
        title: 'Phiên đăng nhập đã hết hạn',
        description: 'Vui lòng đăng nhập lại để tiếp tục.',
        retryable: false,
      }
    }
  }

  return {
    title: 'Không thể tải dữ liệu',
    description: 'Yêu cầu không hợp lệ hoặc dữ liệu hiện không khả dụng.',
    retryable: false,
  }
}
