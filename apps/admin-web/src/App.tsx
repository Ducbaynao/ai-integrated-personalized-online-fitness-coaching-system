import { Navigate, Route, Routes } from 'react-router-dom'
import { AdminLayout } from './components/AdminLayout.tsx'
import { DashboardPage } from './features/dashboard/DashboardPage.tsx'
import { AuthProvider } from './features/auth/AuthContext.tsx'
import { LoginPage } from './features/auth/LoginPage.tsx'
import { ProtectedRoute } from './features/auth/ProtectedRoute.tsx'
import { ExerciseDetailPage } from './features/exercise/ExerciseDetailPage.tsx'
import { ExerciseCreatePage, ExerciseEditPage } from './features/exercise/ExerciseDraftPages.tsx'
import { ExerciseListPage } from './features/exercise/ExerciseListPage.tsx'

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
                <ExerciseListPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="exercises/new"
            element={
              <ProtectedRoute permission="CATALOG_MANAGE">
                <ExerciseCreatePage />
              </ProtectedRoute>
            }
          />
          <Route
            path="exercises/:exerciseId/edit"
            element={
              <ProtectedRoute permission="CATALOG_MANAGE">
                <ExerciseEditPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="exercises/:exerciseId"
            element={
              <ProtectedRoute permission="CATALOG_MANAGE">
                <ExerciseDetailPage />
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
