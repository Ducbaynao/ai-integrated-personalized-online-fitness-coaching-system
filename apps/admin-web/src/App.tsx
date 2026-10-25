import { Navigate, Route, Routes } from 'react-router-dom'
import { AdminLayout } from './components/AdminLayout.tsx'
import { DashboardPage } from './features/dashboard/DashboardPage.tsx'
import { AuthProvider } from './features/auth/AuthContext.tsx'
import { LoginPage } from './features/auth/LoginPage.tsx'
import { ProtectedRoute } from './features/auth/ProtectedRoute.tsx'
import { ExerciseDetailPage } from './features/exercise/ExerciseDetailPage.tsx'
import { ExerciseCreatePage, ExerciseEditPage } from './features/exercise/ExerciseDraftPages.tsx'
import { ExerciseListPage } from './features/exercise/ExerciseListPage.tsx'
import { NutritionCatalogPage } from './features/nutrition/NutritionCatalogPage.tsx'
import { KnowledgeListPage } from './features/knowledge/KnowledgeListPage.tsx'
import { KnowledgeDetailPage } from './features/knowledge/KnowledgeDetailPage.tsx'
import { AiOperationsPage } from './features/ai/AiOperationsPage.tsx'
import { AiRunDetailPage } from './features/ai/AiRunDetailPage.tsx'

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
            path="nutrition/catalog"
            element={
              <ProtectedRoute permission="CATALOG_MANAGE">
                <NutritionCatalogPage />
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
          <Route
            path="knowledge"
            element={
              <ProtectedRoute>
                <KnowledgeListPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="knowledge/:documentId"
            element={
              <ProtectedRoute>
                <KnowledgeDetailPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="ai/operations"
            element={
              <ProtectedRoute>
                <AiOperationsPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="ai/runs/:runId"
            element={
              <ProtectedRoute>
                <AiRunDetailPage />
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
