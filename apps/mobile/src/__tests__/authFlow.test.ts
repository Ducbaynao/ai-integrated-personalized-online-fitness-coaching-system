import { authApi } from '@/services/apiClient';
import { tokenStorage } from '@/services/storage';
import { ApiError, CurrentUserResponse, RegistrationResponse, TokenPairResponse } from '@/types/auth';
import * as SecureStore from 'expo-secure-store';

const originalFetch = globalThis.fetch;

describe('Authentication Flows', () => {
  let fetchMock: jest.Mock;

  beforeEach(() => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    fetchMock = jest.fn();
    globalThis.fetch = fetchMock;
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  describe('Registration Flow', () => {
    it('successfully registers user and receives UserSummary', async () => {
      const mockResponse: RegistrationResponse = {
        user: {
          id: '22222222-2222-2222-2222-222222222222',
          email: 'newuser@example.com',
          displayName: 'New User',
          status: 'PENDING_VERIFICATION',
          preferredLocale: 'vi-VN',
          timezone: 'Asia/Ho_Chi_Minh',
          emailVerifiedAt: null,
          createdAt: '2026-09-20T08:00:00Z',
        },
        verificationRequired: true,
      };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => 'application/json' },
        json: async () => mockResponse,
      });

      const result = await authApi.register({
        email: 'newuser@example.com',
        password: 'securePassword123',
        displayName: 'New User',
      });

      expect(result).toEqual(mockResponse);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/auth/registrations'),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            email: 'newuser@example.com',
            password: 'securePassword123',
            displayName: 'New User',
          }),
        })
      );
    });

    it('throws ApiError with fieldErrors on 409 EmailAlreadyRegistered', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 409,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'EMAIL_ALREADY_REGISTERED',
          message: 'An account with this email already exists',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-409',
          fieldErrors: [],
        }),
      });

      await expect(
        authApi.register({
          email: 'duplicate@example.com',
          password: 'securePassword123',
          displayName: 'Duplicate',
        })
      ).rejects.toThrow(ApiError);
    });
  });

  describe('Email Verification Flow', () => {
    it('successfully confirms email with token', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 204,
        headers: { get: () => 'application/json' },
      });

      await expect(
        authApi.confirmEmail({ token: 'valid-verification-token' })
      ).resolves.toBeUndefined();

      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/auth/email-verifications/confirmations'),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ token: 'valid-verification-token' }),
        })
      );
    });

    it('rejects on invalid or expired token (400)', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 400,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'INVALID_OR_EXPIRED_TOKEN',
          message: 'Verification token has expired or is invalid',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-token-err',
          fieldErrors: [],
        }),
      });

      await expect(
        authApi.confirmEmail({ token: 'bad-token' })
      ).rejects.toThrow('Verification token has expired or is invalid');
    });
  });

  describe('Login and Current User Flow', () => {
    it('successfully logs in and stores tokens', async () => {
      const mockTokenPair: TokenPairResponse = {
        tokenType: 'Bearer',
        accessToken: 'jwt-access-token',
        accessTokenExpiresAt: '2026-09-20T08:15:00Z',
        refreshToken: 'opaque-refresh-token',
        refreshTokenExpiresAt: '2026-10-20T08:00:00Z',
        user: {
          id: '33333333-3333-3333-3333-333333333333',
          email: 'login@example.com',
          displayName: 'Logged In User',
          status: 'ACTIVE',
          preferredLocale: 'vi-VN',
          timezone: 'Asia/Ho_Chi_Minh',
          emailVerifiedAt: '2026-09-20T07:00:00Z',
          createdAt: '2026-09-20T07:00:00Z',
          phoneNumber: null,
          roles: ['STUDENT'],
          capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
          settings: {
            weekStartsOn: 1,
            measurementSystem: 'METRIC',
            accessibilityPreferences: {},
            privacyPreferences: {},
          },
        },
      };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => mockTokenPair,
      });

      const response = await authApi.login({
        email: 'login@example.com',
        password: 'correctPassword',
      });

      expect(response).toEqual(mockTokenPair);

      // Verify tokens stored securely
      const stored = await tokenStorage.getTokens();
      expect(stored?.accessToken).toBe('jwt-access-token');
      expect(stored?.refreshToken).toBe('opaque-refresh-token');
    });

    it('rejects on invalid credentials (401)', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 401,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'INVALID_CREDENTIALS',
          message: 'Bad credentials',
          timestamp: '2026-09-20T08:00:00Z',
          requestId: 'req-bad-cred',
          fieldErrors: [],
        }),
      });

      await expect(
        authApi.login({ email: 'login@example.com', password: 'wrongPassword' })
      ).rejects.toThrow('Bad credentials');

      // Tokens should not be stored
      expect(await tokenStorage.getTokens()).toBeNull();
    });

    it('fetches current user via GET /users/me', async () => {
      await tokenStorage.saveTokens('active-token', 'active-refresh');

      const mockUser: CurrentUserResponse = {
        id: '33333333-3333-3333-3333-333333333333',
        email: 'login@example.com',
        displayName: 'Logged In User',
        status: 'ACTIVE',
        preferredLocale: 'vi-VN',
        timezone: 'Asia/Ho_Chi_Minh',
        emailVerifiedAt: '2026-09-20T07:00:00Z',
        createdAt: '2026-09-20T07:00:00Z',
        phoneNumber: null,
        roles: ['STUDENT'],
        capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
        settings: {
          weekStartsOn: 1,
          measurementSystem: 'METRIC',
          accessibilityPreferences: {},
          privacyPreferences: {},
        },
      };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => mockUser,
      });

      const user = await authApi.getCurrentUser();
      expect(user).toEqual(mockUser);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining('/users/me'),
        expect.objectContaining({
          method: 'GET',
          headers: expect.objectContaining({
            Authorization: 'Bearer active-token',
          }),
        })
      );
    });
  });
});
