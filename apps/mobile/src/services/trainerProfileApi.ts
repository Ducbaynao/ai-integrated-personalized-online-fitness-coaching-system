import { request } from '@/services/apiClient';
import {
  CreateTrainerProfileRequest,
  TrainerProfileResponse,
  UpdateTrainerProfileRequest,
} from '@/types/trainer';

export const trainerProfileApi = {
  /**
   * Activate Trainer capability and create initial Trainer Profile.
   * Path: /trainer-profiles (API base URL includes /api/v1).
   */
  async createTrainerProfile(data: CreateTrainerProfileRequest): Promise<TrainerProfileResponse> {
    return request<TrainerProfileResponse>('/trainer-profiles', {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Get current authenticated user's Trainer Profile.
   * Path: /trainer-profiles/me.
   */
  async getMyTrainerProfile(): Promise<TrainerProfileResponse> {
    return request<TrainerProfileResponse>('/trainer-profiles/me', {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Partially update current user's Trainer Profile.
   * Path: /trainer-profiles/me.
   */
  async updateMyTrainerProfile(data: UpdateTrainerProfileRequest): Promise<TrainerProfileResponse> {
    return request<TrainerProfileResponse>('/trainer-profiles/me', {
      method: 'PATCH',
      requiresAuth: true,
      body: data,
    });
  },
};
