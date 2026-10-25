import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../features/auth/authContextValue.ts'

export function AdminLayout() {
  const auth = useAuth()
  const canManageCatalog = auth.user?.permissions.includes('CATALOG_MANAGE') ?? false
  const canManageKnowledge =
    (auth.user?.roles.includes('ADMIN') ?? false) ||
    (auth.user?.permissions.includes('KNOWLEDGE_REVIEW') ?? false) ||
    (auth.user?.permissions.includes('KNOWLEDGE_IMPORT') ?? false)
  const canManageAi =
    (auth.user?.roles.includes('ADMIN') ?? false) ||
    (auth.user?.permissions.includes('SYSTEM_CONFIG_MANAGE') ?? false)

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
          {canManageCatalog ? <NavLink to="/nutrition/catalog">Danh mục thực phẩm</NavLink> : null}
          {canManageKnowledge ? <NavLink to="/knowledge">Cơ sở tri thức</NavLink> : null}
          {canManageAi ? <NavLink to="/ai/operations">Vận hành AI</NavLink> : null}
        </nav>
        <main className="main-content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
