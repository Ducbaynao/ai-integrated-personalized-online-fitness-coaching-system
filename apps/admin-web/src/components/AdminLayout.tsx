import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../features/auth/authContextValue.ts'

export function AdminLayout() {
  const auth = useAuth()
  const canManageCatalog = auth.user?.permissions.includes('CATALOG_MANAGE') ?? false

  return (
    <div className="app-shell">
      <header className="app-header">
        <NavLink className="brand" to="/" aria-label="Trang chủ quản trị">
          FitCoach Admin
        </NavLink>
        <div className="account-menu">
          <span>{auth.user?.displayName}</span>
          <button className="button-secondary" onClick={() => void auth.signOut()}>
            Đăng xuất
          </button>
        </div>
      </header>
      <div className="app-body">
        <nav className="sidebar" aria-label="Điều hướng quản trị">
          <NavLink to="/" end>Trang tổng quan</NavLink>
          {canManageCatalog ? <NavLink to="/exercises">Bài tập</NavLink> : null}
        </nav>
        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
