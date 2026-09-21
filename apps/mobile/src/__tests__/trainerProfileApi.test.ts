import { trainerProfileApi } from '@/services/trainerProfileApi';
import { tokenStorage } from '@/services/storage';
import { ApiError } from '@/types/auth';
import { TrainerProfileResponse } from '@/types/trainer';
import * as SecureStore from 'expo-secure-store';

const originalFetch = globalThis.fetch;

describe('Trainer Profile API Service', () => {
  let fetchMock: jest.Mock;

  const mockTrainerProfile: TrainerProfileResponse = {
    userId: '22222222-2222-2222-2222-222222222222',
    publicSlug: 'coach-sam',
    bio: 'Strength coach and personal trainer',
    yearsExperience: 6.5,
    acceptingStudents: true,
    verificationStatus: 'NOT_SUBMITTED',
    activityStatus: 'ACTIVE',
    coachingEligibility: {
      eligible: false,
      blockingReasons: ['APPLICATION_NOT_SUBMITTED'],
    },
    verifiedAt: null,
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

  describe('createTrainerProfile', () => {
    it('calls POST /trainer-profiles with Bearer token and returns created trainer profile', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => 'application/json' },
        json: async () => mockTrainerProfile,
      });

      const result = await trainerProfileApi.createTrainerProfile({
        publicSlug: 'coach-sam',
        bio: 'Strength coach and personal trainer',
        yearsExperience: 6.5,
        acceptingStudents: true,
      });

      expect(result).toEqual(mockTrainerProfile);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/trainer-profiles$/),
        expect.objectContaining({
          method: 'POST',
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
            'Content-Type': 'application/json',
          }),
          body: JSON.stringify({
            publicSlug: 'coach-sam',
            bio: 'Strength coach and personal trainer',
            yearsExperience: 6.5,
            acceptingStudents: true,
          }),
        })
      );
    });

    it('supports empty payload for quick setup', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 201,
        headers: { get: () => 'application/json' },
        json: async () => mockTrainerProfile,
      });

      const result = await trainerProfileApi.createTrainerProfile({});
      expect(result).toEqual(mockTrainerProfile);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/trainer-profiles$/),
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({}),
        })
      );
    });

    it('propagates 409 Conflict with TRAINER_SLUG_ALREADY_EXISTS', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 409,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'TRAINER_SLUG_ALREADY_EXISTS',
          message: 'Public slug is already taken',
          timestamp: '2026-09-20T10:00:00Z',
          requestId: 'req-409',
          fieldErrors: [],
        }),
      });

      await expect(
        trainerProfileApi.createTrainerProfile({
          publicSlug: 'existing-slug',
        })
      ).rejects.toThrow(ApiError);
    });
  });

  describe('getMyTrainerProfile', () => {
    it('calls GET /trainer-profiles/me with Bearer token', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => mockTrainerProfile,
      });

      const result = await trainerProfileApi.getMyTrainerProfile();

      expect(result).toEqual(mockTrainerProfile);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/trainer-profiles\/me$/),
        expect.objectContaining({
          method: 'GET',
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
          }),
        })
      );
    });

    it('propagates 403 when capability is not active', async () => {
      fetchMock.mockResolvedValueOnce({
        ok: false,
        status: 403,
        headers: { get: () => 'application/json' },
        json: async () => ({
          errorCode: 'TRAINER_CAPABILITY_UNAVAILABLE',
          message: 'Trainer capability is not active',
          timestamp: '2026-09-20T10:00:00Z',
          requestId: 'req-403',
          fieldErrors: [],
        }),
      });

      await expect(trainerProfileApi.getMyTrainerProfile()).rejects.toThrow(ApiError);
    });
  });

  describe('updateMyTrainerProfile', () => {
    it('calls PATCH /trainer-profiles/me with changed fields and Bearer token', async () => {
      const updatedProfile = { ...mockTrainerProfile, bio: 'Updated bio' };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => updatedProfile,
      });

      const result = await trainerProfileApi.updateMyTrainerProfile({
        bio: 'Updated bio',
      });

      expect(result.bio).toBe('Updated bio');
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/trainer-profiles\/me$/),
        expect.objectContaining({
          method: 'PATCH',
          headers: expect.objectContaining({
            Authorization: 'Bearer mock-access-token',
            'Content-Type': 'application/json',
          }),
          body: JSON.stringify({ bio: 'Updated bio' }),
        })
      );
    });

    it('supports clearing nullable fields by sending null', async () => {
      const clearedProfile = { ...mockTrainerProfile, publicSlug: null, bio: null };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => clearedProfile,
      });

      const result = await trainerProfileApi.updateMyTrainerProfile({
        publicSlug: null,
        bio: null,
      });

      expect(result.publicSlug).toBeNull();
      expect(result.bio).toBeNull();
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/trainer-profiles\/me$/),
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ publicSlug: null, bio: null }),
        })
      );
    });

    it('retains acceptingStudents: false when updating', async () => {
      const notAcceptingProfile = { ...mockTrainerProfile, acceptingStudents: false };

      fetchMock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: { get: () => 'application/json' },
        json: async () => notAcceptingProfile,
      });

      const result = await trainerProfileApi.updateMyTrainerProfile({
        acceptingStudents: false,
      });

      expect(result.acceptingStudents).toBe(false);
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringMatching(/\/trainer-profiles\/me$/),
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ acceptingStudents: false }),
        })
      );
    });
  });
});
