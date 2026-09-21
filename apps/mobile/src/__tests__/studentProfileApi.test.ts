import { studentProfileApi } from '@/services/studentProfileApi';
import { tokenStorage } from '@/services/storage';
import { ApiError } from '@/types/auth';
import { StudentProfileResponse } from '@/types/student';
import * as SecureStore from 'expo-secure-store';

const originalFetch = globalThis.fetch;

describe('Student Profile API Service', () => {
  let fetchMock: jest.Mock;

  const mockProfile: StudentProfileResponse = {
    userId: '11111111-1111-1111-1111-111111111111',
    dateOfBirth: '1995-06-15',
    gender: 'MALE',
    trainingExperienceLevel: 'BEGINNER',
    trainingExperienceMonths: 12,
    availableDaysPerWeek: 4,
    preferredSessionMinutes: 60,
    onboardingCompleted: true,
    onboardingCompletedAt: '2026-09-20T10:00:00Z',
    createdAt: '2026-09-20T10:00:00Z',
    updatedAt: '2026-09-20T10:00:00Z',
  };

  beforeEach(async () => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    await tokenStorage.saveTokens('mock-access-token', 'mock-refresh-token');
    fetchMock = jest.fn();
    globalThis.fetch = fetchMock;
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  describe('createStudentProfile', () => {
    it('calls POST /student-profiles with Bearer token and returns created profile', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => 'application/json' },
        json: async () => mockProfile,
      });

      const result = await studentProfileApi.createStudentProfile({
        dateOfBirth: '1995-06-15',
        gender: 'MALE',
        trainingExperienceLevel: 'BEGINNER',
        trainingExperienceMonths: 12,
        availableDaysPerWeek: 4,
        preferredSessionMinutes: 60,
        onboardingCompleted: true,
      });

      expect(result).toEqual(mockProfile);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/student-profiles$/),
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
            'Content-Type': 'application/json',
          }),
          body: JSON.stringify({
            dateOfBirth: '1995-06-15',
            gender: 'MALE',
            trainingExperienceLevel: 'BEGINNER',
            trainingExperienceMonths: 12,
            availableDaysPerWeek: 4,
            preferredSessionMinutes: 60,
            onboardingCompleted: true,
          }),
        })
      );
    });

    it('propagates 409 Conflict error with ErrorResponse', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 409,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'STUDENT_PROFILE_ALREADY_EXISTS',
          message: 'Student profile already exists',
          timestamp: '2026-09-20T10:00:00Z',
          requestId: 'req-409',
          fieldErrors: [],
        }),
      });

      await expect(
        studentProfileApi.createStudentProfile({
          onboardingCompleted: true,
        })
      ).rejects.toThrow(ApiError);
    });
  });

  describe('getMyStudentProfile', () => {
    it('calls GET /student-profiles/me with Bearer token', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => mockProfile,
      });

      const result = await studentProfileApi.getMyStudentProfile();

      expect(result).toEqual(mockProfile);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/student-profiles\/me$/),
        expect.objectContaining({
          method: 'GET',
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
          }),
        })
      );
    });

    it('propagates 404 NotFound error when profile not found', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 404,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'STUDENT_PROFILE_NOT_FOUND',
          message: 'Student profile not found',
          timestamp: '2026-09-20T10:00:00Z',
          requestId: 'req-404',
          fieldErrors: [],
        }),
      });

      await expect(studentProfileApi.getMyStudentProfile()).rejects.toThrow(ApiError);
    });
  });

  describe('updateMyStudentProfile', () => {
    it('calls PATCH /student-profiles/me with changed fields and Bearer token', async () => {
      const updatedProfile = { ...mockProfile, availableDaysPerWeek: 5 };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => updatedProfile,
      });

      const result = await studentProfileApi.updateMyStudentProfile({
        availableDaysPerWeek: 5,
      });

      expect(result.availableDaysPerWeek).toBe(5);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/student-profiles\/me$/),
        expect.objectContaining({
          method: 'PATCH',
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
            'Content-Type': 'application/json',
          }),
          body: JSON.stringify({ availableDaysPerWeek: 5 }),
        })
      );
    });

    it('supports clearing nullable fields by sending null', async () => {
      const clearedProfile = { ...mockProfile, dateOfBirth: null };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => clearedProfile,
      });

      const result = await studentProfileApi.updateMyStudentProfile({
        dateOfBirth: null,
      });

      expect(result.dateOfBirth).toBeNull();
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/student-profiles\/me$/),
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ dateOfBirth: null }),
        })
      );
    });
  });
});
