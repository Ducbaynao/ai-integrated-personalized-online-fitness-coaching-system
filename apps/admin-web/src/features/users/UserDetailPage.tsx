import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { StatePanel } from '../../components/StatePanel.tsx'
import { AdminActionDialog } from '../../components/AdminActionDialog.tsx'
import { userAdminApi } from '../../services/userAdminApi.ts'
import type { AccountStatus } from '../../types/userAdmin.ts'

export function UserDetailPage() {
  const { userId } = useParams<{ userId: string }>()
  const queryClient = useQueryClient()

  // Dialog state
  const [activeDialog, setActiveDialog] = useState<
    'SUSPEND' | 'RESTORE' | 'DISABLE' | 'FORCE_LOGOUT' | 'ASSIGN_ROLE' | 'REVOKE_ROLE' | null
  >(null)
  const [targetRole, setTargetRole] = useState<string>('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [feedbackMessage, setFeedbackMessage] = useState<string | null>(null)

  const { data: user, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['admin-user-detail', userId],
    queryFn: () => userAdminApi.getUserDetail(userId!),
    enabled: Boolean(userId),
    retry: false,
  })

  const suspendMutation = useMutation({
    mutationFn: (variables: { reason: string; stepUpPassword?: string }) =>
      userAdminApi.suspendUser(userId!, variables),
    onSuccess: (updated) => {
      queryClient.setQueryData(['admin-user-detail', userId], updated)
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      queryClient.invalidateQueries({ queryKey: ['admin-dashboard-summary'] })
      setActiveDialog(null)
      setActionError(null)
      setFeedbackMessage('Tài khoản đã được chuyển sang trạng thái TẠM ĐÌNH CHỈ thành công.')
    },
    onError: (err: any) => {
      setActionError(err?.message || 'Không thể tạm đình chỉ tài khoản.')
    },
  })

  const restoreMutation = useMutation({
    mutationFn: (variables: { reason: string; stepUpPassword?: string }) =>
      userAdminApi.restoreUser(userId!, variables),
    onSuccess: (updated) => {
      queryClient.setQueryData(['admin-user-detail', userId], updated)
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      queryClient.invalidateQueries({ queryKey: ['admin-dashboard-summary'] })
      setActiveDialog(null)
      setActionError(null)
      setFeedbackMessage('Tài khoản đã được KHÔI PHỤC hoạt động thành công.')
    },
    onError: (err: any) => {
      setActionError(err?.message || 'Không thể khôi phục tài khoản.')
    },
  })

  const disableMutation = useMutation({
    mutationFn: (variables: { reason: string; stepUpPassword?: string }) =>
      userAdminApi.disableUser(userId!, variables),
    onSuccess: (updated) => {
      queryClient.setQueryData(['admin-user-detail', userId], updated)
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      queryClient.invalidateQueries({ queryKey: ['admin-dashboard-summary'] })
      setActiveDialog(null)
      setActionError(null)
      setFeedbackMessage('Tài khoản đã bị VÔ HIỆU HÓA vĩnh viễn thành công.')
    },
    onError: (err: any) => {
      setActionError(err?.message || 'Không thể vô hiệu hóa tài khoản.')
    },
  })

  const forceLogoutMutation = useMutation({
    mutationFn: (variables: { reason: string; stepUpPassword?: string }) =>
      userAdminApi.forceLogoutUser(userId!, variables),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['admin-user-detail', userId] })
      setActiveDialog(null)
      setActionError(null)
      setFeedbackMessage(`Đã thu hồi thành công ${res.revokedSessionCount} phiên làm việc của người dùng.`)
    },
    onError: (err: any) => {
      setActionError(err?.message || 'Không thể buộc đăng xuất người dùng.')
    },
  })

  const roleMutation = useMutation({
    mutationFn: (variables: { roleCode: string; action: 'ASSIGN' | 'REVOKE'; reason: string; stepUpPassword?: string }) =>
      userAdminApi.changeUserRole(userId!, variables),
    onSuccess: (updated) => {
      queryClient.setQueryData(['admin-user-detail', userId], updated)
      queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      setActiveDialog(null)
      setActionError(null)
      setFeedbackMessage('Cập nhật vai trò người dùng thành công.')
    },
    onError: (err: any) => {
      setActionError(err?.message || 'Không thể thay đổi vai trò người dùng.')
    },
  })

  const getStatusBadgeClass = (status: AccountStatus) => {
    switch (status) {
      case 'ACTIVE':
        return 'badge-success'
      case 'SUSPENDED':
        return 'badge-warning'
      case 'DISABLED':
      case 'DELETED':
        return 'badge-danger'
      case 'PENDING_VERIFICATION':
      default:
        return 'badge-neutral'
    }
  }

  const isBusy =
    suspendMutation.isPending ||
    restoreMutation.isPending ||
    disableMutation.isPending ||
    forceLogoutMutation.isPending ||
    roleMutation.isPending

  const handleConfirmAction = (reason: string, password: string) => {
    setActionError(null)
    if (activeDialog === 'SUSPEND') {
      suspendMutation.mutate({ reason, stepUpPassword: password })
    } else if (activeDialog === 'RESTORE') {
      restoreMutation.mutate({ reason, stepUpPassword: password })
    } else if (activeDialog === 'DISABLE') {
      disableMutation.mutate({ reason, stepUpPassword: password })
    } else if (activeDialog === 'FORCE_LOGOUT') {
      forceLogoutMutation.mutate({ reason, stepUpPassword: password })
    } else if (activeDialog === 'ASSIGN_ROLE') {
      roleMutation.mutate({ roleCode: targetRole, action: 'ASSIGN', reason, stepUpPassword: password })
    } else if (activeDialog === 'REVOKE_ROLE') {
      roleMutation.mutate({ roleCode: targetRole, action: 'REVOKE', reason, stepUpPassword: password })
    }
  }

  if (isLoading) {
    return (
      <div className="page-container">
        <StatePanel title="Đang tải thông tin chi tiết người dùng…" />
      </div>
    )
  }

  if (isError || !user) {
    return (
      <div className="page-container">
        <StatePanel
          title="Không thể tải thông tin người dùng"
          description={error instanceof Error ? error.message : 'Tài khoản không tồn tại hoặc lỗi kết nối.'}
          tone="danger"
          action={
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button className="button-primary" onClick={() => void refetch()}>
                Thử lại
              </button>
              <Link to="/users" className="button-secondary">
                Quay lại danh sách
              </Link>
            </div>
          }
        />
      </div>
    )
  }

  const availableRolesToAssign = ['TRAINER', 'STUDENT', 'ADMIN', 'CONTENT_ADMIN'].filter(
    (r) => !user.roles.includes(r)
  )

  return (
    <div className="page-container">
      {/* Header & Breadcrumb */}
      <div className="page-header">
        <div>
          <nav className="breadcrumb" aria-label="Điều hướng phân cấp" style={{ marginBottom: '0.5rem' }}>
            <Link to="/users" className="button-link">
              ← Danh sách người dùng
            </Link>
          </nav>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
            <h1 className="page-title">{user.displayName}</h1>
            <span className={`badge ${getStatusBadgeClass(user.status)}`} style={{ fontSize: '0.9rem' }}>
              {user.status}
            </span>
          </div>
          <p className="page-subtitle">{user.email}</p>
        </div>
      </div>

      {/* Success Feedback Banner */}
      {feedbackMessage ? (
        <div
          role="status"
          style={{
            margin: '1rem 0',
            padding: '1rem',
            background: 'var(--color-bg-success-subtle, #def7ec)',
            border: '1px solid var(--color-border-success, #31c48d)',
            borderRadius: '6px',
            color: 'var(--color-text-success, #03543f)',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span>✓ {feedbackMessage}</span>
          <button
            type="button"
            className="button-link"
            onClick={() => setFeedbackMessage(null)}
            style={{ fontWeight: 'bold' }}
          >
            ✕
          </button>
        </div>
      ) : null}

      {/* Action Bar */}
      <div
        className="action-bar surface"
        style={{
          display: 'flex',
          gap: '0.75rem',
          flexWrap: 'wrap',
          alignItems: 'center',
          margin: '1rem 0',
          padding: '1rem',
        }}
      >
        <span style={{ fontWeight: 600, marginRight: '0.5rem' }}>Thao tác quản trị:</span>

        {user.status === 'ACTIVE' ? (
          <button
            type="button"
            className="button-warning"
            onClick={() => {
              setActionError(null)
              setActiveDialog('SUSPEND')
            }}
          >
            Tạm đình chỉ tài khoản
          </button>
        ) : null}

        {user.status === 'SUSPENDED' ? (
          <button
            type="button"
            className="button-primary"
            onClick={() => {
              setActionError(null)
              setActiveDialog('RESTORE')
            }}
          >
            Khôi phục tài khoản
          </button>
        ) : null}

        {user.status !== 'DISABLED' ? (
          <button
            type="button"
            className="button-danger"
            onClick={() => {
              setActionError(null)
              setActiveDialog('DISABLE')
            }}
          >
            Vô hiệu hóa vĩnh viễn
          </button>
        ) : (
          <span className="badge badge-danger">Tài khoản đã bị vô hiệu hóa vĩnh viễn</span>
        )}

        {user.status !== 'DISABLED' ? (
          <button
            type="button"
            className="button-secondary"
            onClick={() => {
              setActionError(null)
              setActiveDialog('FORCE_LOGOUT')
            }}
          >
            Buộc đăng xuất toàn bộ phiên
          </button>
        ) : null}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1.5rem', margin: '1rem 0' }}>
        {/* Account Info Card */}
        <div className="surface">
          <h2>Thông tin tài khoản</h2>
          <dl className="details-list">
            <dt>Mã định danh (ID):</dt>
            <dd><code>{user.id}</code></dd>

            <dt>Email:</dt>
            <dd>{user.email}</dd>

            <dt>Tên hiển thị:</dt>
            <dd>{user.displayName}</dd>

            <dt>Số điện thoại:</dt>
            <dd>{user.phoneNumber || 'Chưa cung cấp'}</dd>

            <dt>Ngôn ngữ & Múi giờ:</dt>
            <dd>{user.preferredLocale} ({user.timezone})</dd>

            <dt>Email đã xác thực:</dt>
            <dd>{user.emailVerifiedAt ? new Date(user.emailVerifiedAt).toLocaleString('vi-VN') : 'Chưa xác thực'}</dd>

            <dt>Ngày tạo tài khoản:</dt>
            <dd>{new Date(user.createdAt).toLocaleString('vi-VN')}</dd>

            <dt>Lần đăng nhập cuối:</dt>
            <dd>{user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString('vi-VN') : 'Chưa đăng nhập'}</dd>
          </dl>
        </div>

        {/* Roles and Permissions Card */}
        <div className="surface">
          <h2>Vai trò & Quyền hạn</h2>
          <div style={{ marginBottom: '1rem' }}>
            <h3 style={{ fontSize: '1rem', marginBottom: '0.5rem' }}>Vai trò hiện có:</h3>
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
              {user.roles && user.roles.length > 0 ? (
                user.roles.map((r) => (
                  <span
                    key={r}
                    className="badge badge-neutral"
                    style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', padding: '0.35rem 0.6rem' }}
                  >
                    {r}
                    <button
                      type="button"
                      aria-label={`Thu hồi vai trò ${r}`}
                      onClick={() => {
                        setTargetRole(r)
                        setActionError(null)
                        setActiveDialog('REVOKE_ROLE')
                      }}
                      style={{
                        background: 'transparent',
                        border: 'none',
                        color: 'inherit',
                        cursor: 'pointer',
                        fontWeight: 'bold',
                        padding: 0,
                      }}
                    >
                      ✕
                    </button>
                  </span>
                ))
              ) : (
                <span className="text-muted">Không có vai trò</span>
              )}
            </div>
          </div>

          {availableRolesToAssign.length > 0 ? (
            <div style={{ marginTop: '1rem', paddingTop: '1rem', borderTop: '1px solid var(--color-border-subtle, #eee)' }}>
              <label htmlFor="assign-role-select" className="field-label">
                Gán thêm vai trò:
              </label>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <select
                  id="assign-role-select"
                  className="select-input"
                  value={targetRole}
                  onChange={(e) => setTargetRole(e.target.value)}
                >
                  <option value="">Chọn vai trò để gán…</option>
                  {availableRolesToAssign.map((r) => (
                    <option key={r} value={r}>
                      {r}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="button-primary"
                  disabled={!targetRole}
                  onClick={() => {
                    setActionError(null)
                    setActiveDialog('ASSIGN_ROLE')
                  }}
                >
                  Gán vai trò
                </button>
              </div>
            </div>
          ) : null}

          <div style={{ marginTop: '1.25rem' }}>
            <h3 style={{ fontSize: '1rem', marginBottom: '0.5rem' }}>Quyền hạn hiệu lực:</h3>
            {user.permissions && user.permissions.length > 0 ? (
              <div style={{ display: 'flex', gap: '0.25rem', flexWrap: 'wrap', maxHeight: '160px', overflowY: 'auto' }}>
                {user.permissions.map((p) => (
                  <code key={p} style={{ fontSize: '0.75rem', padding: '0.15rem 0.35rem', background: '#f4f5f7', borderRadius: '3px' }}>
                    {p}
                  </code>
                ))}
              </div>
            ) : (
              <span className="text-muted">Không có quyền hạn riêng</span>
            )}
          </div>
        </div>
      </div>

      {/* Profiles Detail Section */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1.5rem', margin: '1rem 0' }}>
        {/* Student Profile Card */}
        <div className="surface">
          <h2>Hồ sơ Học viên</h2>
          {user.studentProfile ? (
            <dl className="details-list">
              <dt>Ngày sinh:</dt>
              <dd>{user.studentProfile.dateOfBirth || '—'}</dd>

              <dt>Giới tính:</dt>
              <dd>{user.studentProfile.gender || '—'}</dd>

              <dt>Kinh nghiệm luyện tập:</dt>
              <dd>
                {user.studentProfile.trainingExperienceLevel || '—'}
                {user.studentProfile.trainingExperienceMonths ? ` (${user.studentProfile.trainingExperienceMonths} tháng)` : ''}
              </dd>

              <dt>Số ngày tập / tuần:</dt>
              <dd>{user.studentProfile.availableDaysPerWeek ? `${user.studentProfile.availableDaysPerWeek} ngày` : '—'}</dd>

              <dt>Thời lượng mỗi buổi:</dt>
              <dd>{user.studentProfile.preferredSessionMinutes ? `${user.studentProfile.preferredSessionMinutes} phút` : '—'}</dd>

              <dt>Onboarding:</dt>
              <dd>{user.studentProfile.onboardingCompleted ? '✓ Đã hoàn tất' : 'Chưa hoàn tất'}</dd>
            </dl>
          ) : (
            <p className="text-muted">Người dùng chưa tạo hồ sơ học viên.</p>
          )}
        </div>

        {/* Trainer Profile Card */}
        <div className="surface">
          <h2>Hồ sơ Huấn luyện viên</h2>
          {user.trainerProfile ? (
            <dl className="details-list">
              <dt>Trạng thái xác minh:</dt>
              <dd>
                <span className={`badge ${user.trainerProfile.verificationStatus === 'VERIFIED' ? 'badge-success' : 'badge-warning'}`}>
                  {user.trainerProfile.verificationStatus}
                </span>
              </dd>

              <dt>Trạng thái hoạt động:</dt>
              <dd>{user.trainerProfile.activityStatus}</dd>

              <dt>Số năm kinh nghiệm:</dt>
              <dd>{user.trainerProfile.yearsExperience ? `${user.trainerProfile.yearsExperience} năm` : '—'}</dd>

              <dt>Đang nhận học viên:</dt>
              <dd>{user.trainerProfile.isAcceptingStudents ? '✓ Có' : 'Không'}</dd>

              <dt>Thời gian xác minh:</dt>
              <dd>{user.trainerProfile.verifiedAt ? new Date(user.trainerProfile.verifiedAt).toLocaleString('vi-VN') : 'Chưa xác minh'}</dd>

              <dt>Tiểu sử (Bio):</dt>
              <dd style={{ whiteSpace: 'pre-line' }}>{user.trainerProfile.bio || '—'}</dd>
            </dl>
          ) : (
            <p className="text-muted">Người dùng chưa tạo hồ sơ huấn luyện viên.</p>
          )}
        </div>
      </div>

      {/* Account Status History Timeline */}
      <div className="surface" style={{ marginTop: '1.5rem' }}>
        <h2>Lịch sử thay đổi trạng thái tài khoản (Status History)</h2>
        {user.statusHistory && user.statusHistory.length > 0 ? (
          <div className="table-responsive">
            <table className="data-table" aria-label="Lịch sử trạng thái tài khoản">
              <thead>
                <tr>
                  <th scope="col">Thời gian</th>
                  <th scope="col">Từ trạng thái</th>
                  <th scope="col">Sang trạng thái</th>
                  <th scope="col">Người thực hiện</th>
                  <th scope="col">Lý do</th>
                </tr>
              </thead>
              <tbody>
                {user.statusHistory.map((h) => (
                  <tr key={h.id}>
                    <td>{new Date(h.changedAt).toLocaleString('vi-VN')}</td>
                    <td>
                      {h.fromStatus ? (
                        <span className={`badge ${getStatusBadgeClass(h.fromStatus)}`}>
                          {h.fromStatus}
                        </span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>
                      <span className={`badge ${getStatusBadgeClass(h.toStatus)}`}>
                        {h.toStatus}
                      </span>
                    </td>
                    <td>{h.changedByName || (h.changedBy ? `Admin (${h.changedBy.substring(0, 8)})` : 'Hệ thống')}</td>
                    <td style={{ maxWidth: '350px' }}>{h.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-muted">Chưa ghi nhận thay đổi trạng thái nào cho tài khoản này.</p>
        )}
      </div>

      {/* Sensitive Action Dialogs */}
      <AdminActionDialog
        open={activeDialog === 'SUSPEND'}
        title="Tạm đình chỉ tài khoản người dùng"
        targetName={`${user.displayName} (${user.email})`}
        actionLabel="Xác nhận tạm đình chỉ"
        impactWarning="Người dùng sẽ bị thu hồi toàn bộ phiên đăng nhập, không thể truy cập nền tảng cho đến khi được khôi phục. Các bài tập và lịch trình hiện có vẫn được bảo toàn."
        requiredPermission="USER_SUSPEND"
        destructive={true}
        busy={isBusy}
        errorMessage={actionError}
        onConfirm={handleConfirmAction}
        onCancel={() => {
          setActiveDialog(null)
          setActionError(null)
        }}
      />

      <AdminActionDialog
        open={activeDialog === 'RESTORE'}
        title="Khôi phục tài khoản người dùng"
        targetName={`${user.displayName} (${user.email})`}
        actionLabel="Xác nhận khôi phục"
        impactWarning="Tài khoản sẽ được chuyển lại trạng thái HOẠT ĐỘNG (ACTIVE). Người dùng có thể đăng nhập bình thường. Các phiên làm việc cũ không tự động phục hồi."
        requiredPermission="USER_SUSPEND"
        destructive={false}
        busy={isBusy}
        errorMessage={actionError}
        onConfirm={handleConfirmAction}
        onCancel={() => {
          setActiveDialog(null)
          setActionError(null)
        }}
      />

      <AdminActionDialog
        open={activeDialog === 'DISABLE'}
        title="Vô hiệu hóa vĩnh viễn tài khoản"
        targetName={`${user.displayName} (${user.email})`}
        actionLabel="Xác nhận vô hiệu hóa"
        impactWarning="CẢNH BÁO NGUY HIỂM: Tài khoản sẽ bị vô hiệu hóa vĩnh viễn (DISABLED), toàn bộ phiên làm việc, mã xác thực và yếu tố bảo mật MFA sẽ bị hủy ngay lập tức. Đây là hành động không thể hoàn tác thông thường."
        requiredPermission="USER_DISABLE"
        destructive={true}
        busy={isBusy}
        errorMessage={actionError}
        onConfirm={handleConfirmAction}
        onCancel={() => {
          setActiveDialog(null)
          setActionError(null)
        }}
      />

      <AdminActionDialog
        open={activeDialog === 'FORCE_LOGOUT'}
        title="Buộc đăng xuất toàn bộ phiên"
        targetName={`${user.displayName} (${user.email})`}
        actionLabel="Thu hồi toàn bộ phiên"
        impactWarning="Toàn bộ Refresh Token và phiên đăng nhập hiện tại của người dùng sẽ bị thu hồi ngay lập tức trên tất cả thiết bị. Người dùng sẽ phải nhập mật khẩu để đăng nhập lại."
        requiredPermission="SESSION_REVOKE"
        destructive={false}
        busy={isBusy}
        errorMessage={actionError}
        onConfirm={handleConfirmAction}
        onCancel={() => {
          setActiveDialog(null)
          setActionError(null)
        }}
      />

      <AdminActionDialog
        open={activeDialog === 'ASSIGN_ROLE'}
        title={`Gán vai trò ${targetRole}`}
        targetName={`${user.displayName} (${user.email})`}
        actionLabel="Xác nhận gán vai trò"
        impactWarning={`Người dùng sẽ được cấp thêm quyền hạn tương ứng với vai trò ${targetRole} trên hệ thống.`}
        requiredPermission="USER_ROLE_MANAGE"
        destructive={false}
        busy={isBusy}
        errorMessage={actionError}
        onConfirm={handleConfirmAction}
        onCancel={() => {
          setActiveDialog(null)
          setActionError(null)
        }}
      />

      <AdminActionDialog
        open={activeDialog === 'REVOKE_ROLE'}
        title={`Thu hồi vai trò ${targetRole}`}
        targetName={`${user.displayName} (${user.email})`}
        actionLabel="Xác nhận thu hồi vai trò"
        impactWarning={`Người dùng sẽ bị tước bỏ quyền hạn của vai trò ${targetRole}. Các chức năng thuộc vai trò này sẽ không còn khả dụng.`}
        requiredPermission="USER_ROLE_MANAGE"
        destructive={true}
        busy={isBusy}
        errorMessage={actionError}
        onConfirm={handleConfirmAction}
        onCancel={() => {
          setActiveDialog(null)
          setActionError(null)
        }}
      />
    </div>
  )
}
