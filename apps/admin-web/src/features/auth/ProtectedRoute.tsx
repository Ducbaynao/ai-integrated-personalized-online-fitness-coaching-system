import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { StatePanel } from '../../components/StatePanel.tsx'
import { useAuth } from './authContextValue.ts'

interface ProtectedRouteProps {
  children: ReactNode
  permission?: string
}

export function ProtectedRoute({ children, permission }: ProtectedRouteProps) {
  const auth = useAuth()
  const location = useLocation()

  if (auth.status === 'loading') {
    return <StatePanel title="Đang kiểm tra phiên đăng nhập…" />
  }

  if (auth.status === 'error') {
    return (
      <StatePanel
        title="Không thể kiểm tra phiên đăng nhập"
        description="Vui lòng kiểm tra kết nối mạng rồi thử lại."
        action={<button onClick={auth.retryBootstrap}>Thử lại</button>}
        tone="danger"
      />
    )
  }

  if (auth.status === 'unauthenticated') {
    return <Navigate to="/login" replace state={{ from: location }} />
  }

  if (auth.user?.status !== 'ACTIVE') {
    return (
      <StatePanel
        title="Tài khoản không khả dụng"
        description="Tài khoản của bạn hiện không thể truy cập trang quản trị."
        tone="danger"
      />
    )
  }

  if (permission && !auth.user.permissions.includes(permission)) {
    return (
      <StatePanel
        title="Bạn không có quyền truy cập"
        description="Hãy liên hệ quản trị viên nếu bạn cần sử dụng chức năng này."
        tone="danger"
      />
    )
  }

  return children
}
