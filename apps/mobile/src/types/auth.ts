export type AccountStatus =
  | 'PENDING_VERIFICATION'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'DISABLED'
  | 'DELETION_PENDING'
  | 'DELETED';

export type RoleCode =
  | 'STUDENT'
  | 'TRAINER'
  | 'ADMIN'
  | 'SUPER_ADMIN'
  | 'USER_ADMIN'
  | 'CONTENT_ADMIN'
  | 'SUPPORT_ADMIN'
  | 'MODERATION_ADMIN';

export type MeasurementSystem = 'METRIC' | 'IMPERIAL';

export interface UserCapabilities {
  hasStudentProfile: boolean;
  hasTrainerProfile: boolean;
  canCoach: boolean;
}

export interface UserSettings {
  weekStartsOn: number;
  measurementSystem: MeasurementSystem;
  accessibilityPreferences: Record<string, unknown>;
  privacyPreferences: Record<string, unknown>;
}

export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
  status: AccountStatus;
  preferredLocale: string;
  timezone: string;
  emailVerifiedAt: string | null;
  createdAt: string;
}

export interface CurrentUserResponse extends UserSummary {
  phoneNumber: string | null;
  roles: RoleCode[];
  capabilities: UserCapabilities;
  settings: UserSettings;
}

export interface TokenPairResponse {
  tokenType: 'Bearer';
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  user: CurrentUserResponse;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  preferredLocale?: string;
  timezone?: string;
}

export interface RegistrationResponse {
  user: UserSummary;
  verificationRequired: boolean;
}

export interface ConfirmEmailRequest {
  token: string;
}

export interface LoginRequest {
  email: string;
  password: string;
  deviceName?: string;
}

export interface RefreshTokenRequest {
  refreshToken: string;
  deviceName?: string;
}

export interface LogoutRequest {
  refreshToken: string;
}

export interface FieldError {
  field: string;
  code: string;
  message: string;
}

export interface ErrorResponse {
  errorCode: string;
  message: string;
  timestamp: string;
  requestId: string;
  fieldErrors: FieldError[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly errorResponse?: ErrorResponse;

  constructor(status: number, message: string, errorResponse?: ErrorResponse) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.errorResponse = errorResponse;
    Object.setPrototypeOf(this, ApiError.prototype);
  }
}
