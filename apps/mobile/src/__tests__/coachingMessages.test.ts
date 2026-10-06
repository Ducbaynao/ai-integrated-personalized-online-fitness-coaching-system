import { coachingErrorMessage, isStaleCoachingError } from '@/features/coaching/coachingMessages';
import { ApiError } from '@/types/auth';

describe('coaching error presentation', () => {
  it('maps stable authority and concealment errors without exposing backend detail', () => {
    const denied = new ApiError(403, 'internal policy detail', { errorCode: 'ACCESS_DENIED', message: 'internal policy detail', timestamp: '2026-10-06T00:00:00Z', requestId: 'request-1', fieldErrors: [] });
    const concealed = new ApiError(404, 'not found', { errorCode: 'COACHING_RELATIONSHIP_NOT_FOUND', message: 'not found', timestamp: '2026-10-06T00:00:00Z', requestId: 'request-2', fieldErrors: [] });
    expect(coachingErrorMessage(denied)).toBe('Bạn không có quyền thực hiện thao tác này.');
    expect(coachingErrorMessage(concealed)).toContain('không tồn tại hoặc bạn không còn quyền xem');
  });

  it('distinguishes network/server errors and detects stale conflicts', () => {
    expect(coachingErrorMessage(new ApiError(0, 'offline'))).toContain('kết nối mạng');
    expect(coachingErrorMessage(new ApiError(503, 'down'))).toContain('Máy chủ');
    expect(isStaleCoachingError(new ApiError(409, 'stale', { errorCode: 'COACHING_VERSION_CONFLICT', message: 'stale', timestamp: '2026-10-06T00:00:00Z', requestId: 'request-3', fieldErrors: [] }))).toBe(true);
    expect(isStaleCoachingError(new ApiError(409, 'other'))).toBe(false);
  });
});
