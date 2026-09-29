import { Navigate, Route, Routes } from 'react-router-dom'
import { AdminLayout } from './components/AdminLayout.tsx'
import { DashboardPage } from './features/dashboard/DashboardPage.tsx'
import { AuthProvider } from './features/auth/AuthContext.tsx'
import { LoginPage } from './features/auth/LoginPage.tsx'
import { ProtectedRoute } from './features/auth/ProtectedRoute.tsx'
import { ExerciseCatalogPlaceholder } from './features/exercise/ExerciseCatalogPlaceholder.tsx'

function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <ProtectedRoute>
              <AdminLayout />
            </ProtectedRoute>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route
            path="exercises"
            element={
              <ProtectedRoute permission="CATALOG_MANAGE">
                <ExerciseCatalogPlaceholder />
              </ProtectedRoute>
            }
          />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}

export default App
