import { apiRequest } from './apiClient.ts'
import type {
  AdminTrainerApplicationPageResponse,
  AdminTrainerApplicationResponse,
  DecideTrainerApplicationRequest,
} from '../types/trainerApplication.ts'

export const trainerApplicationApi = {
  getApplications: (params?: { status?: string; page?: number; size?: number }) => {
    const searchParams = new URLSearchParams()
    if (params?.status) searchParams.set('status', params.status)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    const path = `/admin/trainer-applications${query ? `?${query}` : ''}`
    return apiRequest<AdminTrainerApplicationPageResponse>(path)
  },

  getApplicationDetail: (applicationId: string) =>
    apiRequest<AdminTrainerApplicationResponse>(`/admin/trainer-applications/${applicationId}`),

  decideApplication: (applicationId: string, payload: DecideTrainerApplicationRequest) =>
    apiRequest<AdminTrainerApplicationResponse>(`/admin/trainer-applications/${applicationId}/decisions`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
}
