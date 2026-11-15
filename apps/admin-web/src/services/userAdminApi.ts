import { apiRequest } from './apiClient.ts'
import type {
  AdminDashboardSummaryResponse,
  AdminUserActionRequest,
  AdminUserDetailResponse,
  AdminUserPageResponse,
  ChangeUserRoleRequest,
  ForceLogoutResponse,
  StepUpRequest,
  StepUpResponse,
} from '../types/userAdmin.ts'

export const userAdminApi = {
  getUsers: (params?: {
    search?: string
    status?: string
    role?: string
    page?: number
    size?: number
  }) => {
    const searchParams = new URLSearchParams()
    if (params?.search) searchParams.set('search', params.search)
    if (params?.status) searchParams.set('status', params.status)
    if (params?.role) searchParams.set('role', params.role)
    if (params?.page !== undefined) searchParams.set('page', params.page.toString())
    if (params?.size !== undefined) searchParams.set('size', params.size.toString())

    const query = searchParams.toString()
    const path = `/admin/users${query ? `?${query}` : ''}`
    return apiRequest<AdminUserPageResponse>(path)
  },

  getUserDetail: (userId: string) =>
    apiRequest<AdminUserDetailResponse>(`/admin/users/${userId}`),

  suspendUser: (userId: string, payload: AdminUserActionRequest) =>
    apiRequest<AdminUserDetailResponse>(`/admin/users/${userId}/suspend`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  restoreUser: (userId: string, payload: AdminUserActionRequest) =>
    apiRequest<AdminUserDetailResponse>(`/admin/users/${userId}/restore`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  disableUser: (userId: string, payload: AdminUserActionRequest) =>
    apiRequest<AdminUserDetailResponse>(`/admin/users/${userId}/disable`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  forceLogoutUser: (userId: string, payload: AdminUserActionRequest) =>
    apiRequest<ForceLogoutResponse>(`/admin/users/${userId}/force-logout`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  changeUserRole: (userId: string, payload: ChangeUserRoleRequest) =>
    apiRequest<AdminUserDetailResponse>(`/admin/users/${userId}/roles`, {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  issueStepUp: (payload: StepUpRequest) =>
    apiRequest<StepUpResponse>('/admin/auth/step-up', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  getDashboardSummary: () =>
    apiRequest<AdminDashboardSummaryResponse>('/admin/dashboard/summary'),
}
