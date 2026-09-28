import { getExerciseCatalogErrorCopy, isExerciseAccessError } from '@/features/exercise/exerciseMessages';
import { ApiError, ErrorResponse } from '@/types/auth';

function apiError(status: number, errorCode: string, backendMessage: string): ApiError {
  const response: ErrorResponse = {
    errorCode,
    message: backendMessage,
    timestamp: '2026-09-28T00:00:00Z',
    requestId: 'request-1',
    fieldErrors: [],
  };
  return new ApiError(status, backendMessage, response);
}

describe('exercise catalog error copy', () => {
  it.each([
    ['ACCESS_DENIED', 403, 'Bạn chưa có quyền xem thư viện'],
    ['ACCOUNT_UNAVAILABLE', 403, 'Tài khoản hiện không khả dụng'],
    ['VALIDATION_FAILED', 400, 'Thông tin tìm kiếm chưa hợp lệ'],
    ['AUTH_TOKEN_EXPIRED', 401, 'Phiên đăng nhập đã hết hạn'],
  ])('maps stable code %s to Vietnamese copy', (code, status, expectedTitle) => {
    const rawBackendMessage = 'Raw backend message must not be shown';
    const copy = getExerciseCatalogErrorCopy(apiError(status as number, code, rawBackendMessage));

    expect(copy.title).toBe(expectedTitle);
    expect(copy.title).not.toContain(rawBackendMessage);
    expect(copy.message).not.toContain(rawBackendMessage);
  });

  it('maps network and server failures without exposing their raw messages', () => {
    expect(getExerciseCatalogErrorCopy(new ApiError(0, 'Network failed'))).toEqual({
      title: 'Không thể kết nối đến máy chủ',
      message: 'Hãy kiểm tra kết nối mạng rồi thử lại.',
      kind: 'network',
    });
    expect(getExerciseCatalogErrorCopy(new ApiError(503, 'Internal stack trace'))).toEqual({
      title: 'Máy chủ đang gặp sự cố',
      message: 'Vui lòng thử lại sau ít phút.',
      kind: 'server',
    });
  });

  it('classifies permission and session errors as access errors', () => {
    expect(isExerciseAccessError(apiError(403, 'ACCESS_DENIED', 'Forbidden'))).toBe(true);
    expect(isExerciseAccessError(apiError(401, 'UNAUTHORIZED', 'Unauthorized'))).toBe(true);
    expect(isExerciseAccessError(new ApiError(0, 'Offline'))).toBe(false);
  });
});
