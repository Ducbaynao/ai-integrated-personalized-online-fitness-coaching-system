import { ApiError } from '@/types/auth';
import {
  EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION,
  ExerciseDifficulty,
} from '@/types/exercise';

export const EXERCISE_DIFFICULTY_LABELS: Record<ExerciseDifficulty, string> = {
  BEGINNER: 'Cơ bản',
  INTERMEDIATE: 'Trung cấp',
  ADVANCED: 'Nâng cao',
};

export const EXERCISE_FILTER_LIMIT_MESSAGE =
  `Tối đa ${EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION} lựa chọn cho mỗi nhóm bộ lọc.`;

export interface ExerciseErrorCopy {
  title: string;
  message: string;
  kind: 'permission' | 'session' | 'validation' | 'network' | 'server' | 'generic';
}

const ERROR_CODE_COPY: Record<string, ExerciseErrorCopy> = {
  ACCESS_DENIED: {
    title: 'Bạn chưa có quyền xem thư viện',
    message: 'Tài khoản cần có hồ sơ học viên hoặc huấn luyện viên để xem thư viện bài tập.',
    kind: 'permission',
  },
  ACCOUNT_UNAVAILABLE: {
    title: 'Tài khoản hiện không khả dụng',
    message: 'Vui lòng kiểm tra trạng thái tài khoản hoặc liên hệ bộ phận hỗ trợ.',
    kind: 'permission',
  },
  UNAUTHORIZED: {
    title: 'Phiên đăng nhập không còn hiệu lực',
    message: 'Vui lòng đăng nhập lại để tiếp tục.',
    kind: 'session',
  },
  AUTH_TOKEN_EXPIRED: {
    title: 'Phiên đăng nhập đã hết hạn',
    message: 'Vui lòng đăng nhập lại để tiếp tục.',
    kind: 'session',
  },
  AUTH_SESSION_REVOKED: {
    title: 'Phiên đăng nhập không còn hiệu lực',
    message: 'Vui lòng đăng nhập lại để tiếp tục.',
    kind: 'session',
  },
  INVALID_REFRESH_TOKEN: {
    title: 'Phiên đăng nhập đã hết hạn',
    message: 'Vui lòng đăng nhập lại để tiếp tục.',
    kind: 'session',
  },
  VALIDATION_FAILED: {
    title: 'Thông tin tìm kiếm chưa hợp lệ',
    message: 'Vui lòng điều chỉnh từ khóa hoặc bộ lọc rồi thử lại.',
    kind: 'validation',
  },
};

export function getExerciseCatalogErrorCopy(error: unknown): ExerciseErrorCopy {
  if (error instanceof ApiError) {
    const errorCode = error.errorResponse?.errorCode;
    if (errorCode && ERROR_CODE_COPY[errorCode]) {
      return ERROR_CODE_COPY[errorCode];
    }

    if (error.status === 401) {
      return ERROR_CODE_COPY.UNAUTHORIZED;
    }
    if (error.status === 403) {
      return ERROR_CODE_COPY.ACCESS_DENIED;
    }
    if (error.status === 400) {
      return ERROR_CODE_COPY.VALIDATION_FAILED;
    }
    if (error.status === 0) {
      return {
        title: 'Không thể kết nối đến máy chủ',
        message: 'Hãy kiểm tra kết nối mạng rồi thử lại.',
        kind: 'network',
      };
    }
    if (error.status >= 500) {
      return {
        title: 'Máy chủ đang gặp sự cố',
        message: 'Vui lòng thử lại sau ít phút.',
        kind: 'server',
      };
    }
  }

  return {
    title: 'Không thể tải thư viện bài tập',
    message: 'Đã xảy ra lỗi. Vui lòng thử lại.',
    kind: 'generic',
  };
}

export function isExerciseAccessError(error: unknown): boolean {
  const kind = getExerciseCatalogErrorCopy(error).kind;
  return kind === 'permission' || kind === 'session';
}
