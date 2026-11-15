import { request } from '@/services/apiClient';
import { ProgressDashboardSummary, ProgressScope } from '@/types/measurement';

export const progressDashboardApi = {
  /**
   * Fetches deterministic progress summary snapshot grounded in Progress Engine facts.
   * Works independently from AI services.
   */
  async getSummary(params?: {
    studentId?: string;
    scope?: ProgressScope;
    fromDate?: string;
    toDate?: string;
  }): Promise<ProgressDashboardSummary> {
    const query = new URLSearchParams();
    if (params?.studentId) query.set('studentId', params.studentId);
    if (params?.scope) query.set('scope', params.scope);
    if (params?.fromDate) query.set('fromDate', params.fromDate);
    if (params?.toDate) query.set('toDate', params.toDate);

    const queryString = query.toString();
    const path = queryString ? `/progress/summary?${queryString}` : '/progress/summary';
    return request<ProgressDashboardSummary>(path, {
      method: 'GET',
      requiresAuth: true,
    });
  },
};
