import { request } from '@/services/apiClient';
import {
  CreateStudentProfileRequest,
  StudentProfileResponse,
  UpdateStudentProfileRequest,
} from '@/types/student';

export const studentProfileApi = {
  /**
   * Activate Student capability and create initial Student Profile.
   * Path: /student-profiles (API base URL includes /api/v1).
   */
  async createStudentProfile(data: CreateStudentProfileRequest): Promise<StudentProfileResponse> {
    return request<StudentProfileResponse>('/student-profiles', {
      method: 'POST',
      requiresAuth: true,
      body: data,
    });
  },

  /**
   * Get current authenticated user's Student Profile.
   * Path: /student-profiles/me.
   */
  async getMyStudentProfile(): Promise<StudentProfileResponse> {
    return request<StudentProfileResponse>('/student-profiles/me', {
      method: 'GET',
      requiresAuth: true,
    });
  },

  /**
   * Partially update current user's Student Profile.
   * Path: /student-profiles/me.
   */
  async updateMyStudentProfile(data: UpdateStudentProfileRequest): Promise<StudentProfileResponse> {
    return request<StudentProfileResponse>('/student-profiles/me', {
      method: 'PATCH',
      requiresAuth: true,
      body: data,
    });
  },
};
