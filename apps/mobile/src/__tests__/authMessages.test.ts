import { getAuthErrorMessage, localizeFieldErrors } from '@/features/auth/authMessages';
import { ApiError } from '@/types/auth';

describe('Vietnamese authentication error mapping', () => {
  it('maps stable backend codes without exposing server copy', () => {
    const error = new ApiError(401, 'Raw backend message', {
      errorCode: 'INVALID_CREDENTIALS',
      message: 'Raw backend message',
      timestamp: '2026-09-22T00:00:00Z',
      requestId: 'request-1',
      fieldErrors: [],
    });

    expect(getAuthErrorMessage(error, 'Fallback')).toBe('Email hoặc mật khẩu không đúng.');
  });

  it('uses actionable messages for network and server failures', () => {
    expect(getAuthErrorMessage(new ApiError(0, 'Network failed'), 'Fallback')).toContain(
      'kiểm tra mạng'
    );
    expect(getAuthErrorMessage(new ApiError(503, 'Unavailable'), 'Fallback')).toContain(
      'Máy chủ'
    );
  });

  it('localizes known fields and safely handles unknown fields', () => {
    expect(
      localizeFieldErrors([
        { field: 'email', code: 'INVALID', message: 'must be valid' },
        { field: 'customField', code: 'INVALID', message: 'raw validation detail' },
      ])
    ).toEqual({
      email: 'Email chưa hợp lệ.',
      customField: 'Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.',
    });
  });
});
