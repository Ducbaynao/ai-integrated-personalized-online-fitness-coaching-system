import { request } from '@/services/apiClient';
import {
  SubmitTrainerApplicationRequest,
  TrainerApplicationResponse,
} from '@/types/trainer';

export const trainerApplicationApi = {
  /**
   * Submit an initial or resubmitted Trainer verification application.
   * Path: /trainer-applications (API base URL includes /api/v1).
   */
  async submitApplication(data: SubmitTrainerApplicationRequest): Promise<TrainerApplicationResponse> {
    return request<TrainerApplicationResponse>('/trainer-applications', {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Get current authenticated trainer's verification application.
   * Path: /trainer-applications/me/current.
   */
  async getCurrentApplication(): Promise<TrainerApplicationResponse> {
    return request<TrainerApplicationResponse>('/trainer-applications/me/current', {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
