import { ApiError, FieldError } from '@/types/auth';

const ERROR_MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Email hoặc mật khẩu không đúng.',
  ACCOUNT_UNAVAILABLE: 'Tài khoản hiện không thể đăng nhập. Vui lòng liên hệ hỗ trợ.',
  EMAIL_ALREADY_REGISTERED: 'Email này đã được sử dụng cho một tài khoản khác.',
  INVALID_OR_EXPIRED_TOKEN: 'Mã xác minh không hợp lệ hoặc đã hết hạn.',
  INVALID_REFRESH_TOKEN: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  AUTH_TOKEN_EXPIRED: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  AUTH_SESSION_REVOKED: 'Phiên đăng nhập không còn hiệu lực. Vui lòng đăng nhập lại.',
};

const FIELD_MESSAGES: Record<string, string> = {
  displayName: 'Họ và tên chưa hợp lệ.',
  email: 'Email chưa hợp lệ.',
  password: 'Mật khẩu chưa đáp ứng yêu cầu.',
  token: 'Mã xác minh chưa hợp lệ.',
};

export function getAuthErrorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) {
    return fallback;
  }

  if (error.status === 0) {
    return 'Không thể kết nối đến máy chủ. Hãy kiểm tra mạng và thử lại.';
  }

  const errorCode = error.errorResponse?.errorCode;
  if (errorCode && ERROR_MESSAGES[errorCode]) {
    return ERROR_MESSAGES[errorCode];
  }

  if (error.status >= 500) {
    return 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';
  }

  return fallback;
}

export function localizeFieldErrors(fieldErrors: FieldError[] | undefined): Record<string, string> {
  const localized: Record<string, string> = {};

  fieldErrors?.forEach((fieldError) => {
    localized[fieldError.field] =
      FIELD_MESSAGES[fieldError.field] ?? 'Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.';
  });

  return localized;
}
