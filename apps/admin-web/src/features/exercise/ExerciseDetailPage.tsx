import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'
import { StatePanel } from '../../components/StatePanel.tsx'
import { ApiError, isRetryableApiError } from '../../services/apiClient.ts'
import {
  activateAdminExercise,
  archiveAdminExercise,
  exerciseQueryKeys,
  getAdminExerciseDetail,
} from '../../services/exerciseApi.ts'
import type { AdminExerciseVariation } from '../../types/exercise.ts'
import {
  difficultyLabels,
  formatDateTime,
  getExerciseErrorContent,
  lifecycleLabels,
} from './exercisePresentation.ts'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

interface DetailLocationState {
  from?: string
}

type LifecycleAction = 'activate' | 'archive'

interface ActionSnapshot {
  action: LifecycleAction
  exerciseId: string
  exerciseName: string
  expectedVersion: number
}

interface ActionErrorContent {
  title: string
  description: string
  refreshRecommended?: boolean
}

interface TerminalActionError {
  title: string
  description: string
}

export function ExerciseDetailPage() {
  const { exerciseId = '' } = useParams()
  return <ExerciseDetailPageContent key={exerciseId} exerciseId={exerciseId} />
}

function ExerciseDetailPageContent({ exerciseId }: { exerciseId: string }) {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isValidExerciseId = UUID_PATTERN.test(exerciseId)
  const [actionSnapshot, setActionSnapshot] = useState<ActionSnapshot>()
  const [archiveReason, setArchiveReason] = useState('')
  const [archiveReasonError, setArchiveReasonError] = useState<string>()
  const [actionError, setActionError] = useState<ActionErrorContent>()
  const [terminalActionError, setTerminalActionError] = useState<TerminalActionError>()
  const [successMessage, setSuccessMessage] = useState<string>()
  const detailQuery = useQuery({
    queryKey: exerciseQueryKeys.detail(exerciseId),
    queryFn: () => getAdminExerciseDetail(exerciseId),
    enabled: isValidExerciseId && !terminalActionError,
  })
  const actionMutation = useMutation({
    mutationFn: async ({ snapshot, reason }: { snapshot: ActionSnapshot; reason?: string }) => {
      if (snapshot.action === 'activate') {
        return activateAdminExercise(snapshot.exerciseId, { expectedVersion: snapshot.expectedVersion })
      }
      return archiveAdminExercise(snapshot.exerciseId, {
        expectedVersion: snapshot.expectedVersion,
        reason: reason!,
      })
    },
    onSuccess: async (updated, variables) => {
      queryClient.setQueryData(exerciseQueryKeys.detail(updated.id), updated)
      await queryClient.invalidateQueries({ queryKey: exerciseQueryKeys.lists() })
      setActionSnapshot(undefined)
      setArchiveReason('')
      setArchiveReasonError(undefined)
      setActionError(undefined)
      setSuccessMessage(variables.snapshot.action === 'activate'
        ? `Đã kích hoạt ${updated.name}.`
        : `Đã lưu trữ ${updated.name}.`)
    },
    onError: (error) => {
      const mapped = mapLifecycleActionError(error)
      setActionSnapshot(undefined)
      if (mapped.terminal) {
        setTerminalActionError(mapped.terminal)
      } else {
        setActionError(mapped.content)
      }
    },
  })
  const state = location.state as DetailLocationState | null
  const goBack = () => navigate(state?.from?.startsWith('/exercises') ? state.from : '/exercises')

  useEffect(() => {
    if (terminalActionError) {
      queryClient.removeQueries({ queryKey: exerciseQueryKeys.detail(exerciseId), exact: true })
    }
  }, [exerciseId, queryClient, terminalActionError])

  if (!isValidExerciseId) {
    return (
      <DetailState
        title="Đường dẫn bài tập không hợp lệ"
        description="Hãy quay lại danh sách và chọn một bài tập khác."
        onBack={goBack}
      />
    )
  }


  if (terminalActionError) {
    return (
      <DetailState
        title={terminalActionError.title}
        description={terminalActionError.description}
        onBack={goBack}
      />
    )
  }

  if (detailQuery.isPending) {
    return (
      <section className="page-stack" aria-busy="true">
        <BackButton onBack={goBack} />
        <div className="surface skeleton-stack" aria-label="Đang tải chi tiết bài tập">
          <div className="skeleton-line skeleton-line--short" />
          <div className="skeleton-line" />
          <div className="skeleton-line" />
        </div>
      </section>
    )
  }

  const canRenderStaleDetail = detailQuery.isError && detailQuery.data && isRetryableApiError(detailQuery.error)
  if (detailQuery.isError && !canRenderStaleDetail) {
    const content = getExerciseErrorContent(detailQuery.error, 'detail')
    return (
      <DetailState
        title={content.title}
        description={content.description}
        onBack={goBack}
        onRetry={content.retryable ? () => void detailQuery.refetch() : undefined}
      />
    )
  }

  const exercise = detailQuery.data
  if (!exercise) return null

  const openAction = (action: LifecycleAction) => {
    setActionError(undefined)
    setSuccessMessage(undefined)
    setArchiveReasonError(undefined)
    if (action === 'archive') setArchiveReason('')
    setActionSnapshot({
      action,
      exerciseId: exercise.id,
      exerciseName: exercise.name,
      expectedVersion: exercise.version,
    })
  }
  const closeAction = () => {
    if (!actionMutation.isPending) setActionSnapshot(undefined)
  }
  const confirmAction = () => {
    if (!actionSnapshot || actionMutation.isPending) return
    if (actionSnapshot.action === 'archive') {
      const reason = archiveReason.trim()
      if (reason.length < 1 || reason.length > 1000) {
        setArchiveReasonError('Lý do lưu trữ phải có từ 1 đến 1000 ký tự.')
        return
      }
      actionMutation.mutate({ snapshot: actionSnapshot, reason })
      return
    }
    actionMutation.mutate({ snapshot: actionSnapshot })
  }

  return (
    <section className="page-stack">
      <BackButton onBack={goBack} />
      {canRenderStaleDetail ? (
        <div className="inline-alert" role="status">
          Không thể cập nhật dữ liệu mới. Chi tiết gần nhất vẫn đang được hiển thị.
          <button className="button-link-inline" onClick={() => void detailQuery.refetch()}>Thử lại</button>
        </div>
      ) : null}
      {successMessage ? <div className="success-alert" role="status">{successMessage}</div> : null}
      {actionError ? (
        <div className="conflict-panel" role="alert">
          <h2>{actionError.title}</h2>
          <p>{actionError.description}</p>
          {actionError.refreshRecommended ? (
            <button className="button-secondary" onClick={() => {
              setActionError(undefined)
              void detailQuery.refetch()
            }}>
              Tải lại chi tiết
            </button>
          ) : null}
        </div>
      ) : null}

      <header className="detail-heading">
        <div>
          <p className="eyebrow">Chi tiết bài tập</p>
          <h1>{exercise.name}</h1>
          <p className="muted">{exercise.code}</p>
        </div>
        <div className="detail-actions">
          <span className={`status-badge status-badge--${exercise.status.toLowerCase()}`}>
            {lifecycleLabels[exercise.status]}
          </span>
          {exercise.status === 'DRAFT' ? (
            <>
              <Link className="button-link" to={`/exercises/${exercise.id}/edit`}>Chỉnh sửa</Link>
              <button type="button" disabled={actionMutation.isPending} onClick={() => openAction('activate')}>
                Kích hoạt
              </button>
            </>
          ) : null}
          {exercise.status === 'ACTIVE' ? (
            <button
              type="button"
              className="button-danger"
              disabled={actionMutation.isPending}
              onClick={() => openAction('archive')}
            >
              Lưu trữ
            </button>
          ) : null}
        </div>
      </header>

      <section className="surface" aria-labelledby="general-heading">
        <h2 id="general-heading">Thông tin chung</h2>
        <dl className="detail-grid">
          <DetailField label="Danh mục" value={exercise.categoryCode} />
          <DetailField label="Độ khó" value={exercise.difficulty ? difficultyLabels[exercise.difficulty] : null} />
          <DetailField label="Kiểu vận động" value={exercise.movementPattern} />
          <DetailField label="Một bên cơ thể" value={exercise.unilateral ? 'Có' : 'Không'} />
          <DetailField label="Phiên bản" value={exercise.version.toLocaleString('vi-VN')} />
          <DetailField label="Tạo lúc" value={formatDateTime(exercise.createdAt)} />
          <DetailField label="Cập nhật lúc" value={formatDateTime(exercise.updatedAt)} />
        </dl>
      </section>

      {exercise.description || exercise.instructions ? (
        <section className="surface prose-section" aria-labelledby="content-heading">
          <h2 id="content-heading">Nội dung hướng dẫn</h2>
          {exercise.description ? <div><h3>Mô tả</h3><p>{exercise.description}</p></div> : null}
          {exercise.instructions ? <div><h3>Hướng dẫn</h3><p className="pre-line">{exercise.instructions}</p></div> : null}
        </section>
      ) : null}

      {exercise.tagCodes.length > 0 ? (
        <section className="surface" aria-labelledby="tags-heading">
          <h2 id="tags-heading">Nhãn</h2>
          <ul className="chip-list">
            {exercise.tagCodes.map((tag) => <li key={tag}>{tag}</li>)}
          </ul>
        </section>
      ) : null}

      {exercise.canonicalReplacementId ? (
        <section className="surface" aria-labelledby="replacement-heading">
          <h2 id="replacement-heading">Bài tập thay thế chuẩn</h2>
          <p className="code-value">{exercise.canonicalReplacementId}</p>
        </section>
      ) : null}

      <section className="surface" aria-labelledby="variations-heading">
        <h2 id="variations-heading">Biến thể ({exercise.variations.length.toLocaleString('vi-VN')})</h2>
        {exercise.variations.length === 0 ? (
          <p className="muted">Chưa có biến thể.</p>
        ) : (
          <div className="variation-list">
            {exercise.variations.map((variation) => (
              <VariationCard key={variation.id} variation={variation} />
            ))}
          </div>
        )}
      </section>
      <ConfirmationDialog
        open={actionSnapshot?.action === 'activate'}
        title="Kích hoạt bài tập?"
        description={`Bài tập “${actionSnapshot?.exerciseName ?? ''}” sẽ xuất hiện trong danh mục đang hoạt động sau khi hệ thống xác thực nội dung.`}
        confirmLabel="Kích hoạt bài tập"
        cancelLabel="Hủy"
        busy={actionMutation.isPending}
        onCancel={closeAction}
        onConfirm={confirmAction}
      />
      <ConfirmationDialog
        open={actionSnapshot?.action === 'archive'}
        title="Lưu trữ bài tập?"
        description={`Bài tập “${actionSnapshot?.exerciseName ?? ''}” sẽ không còn dùng được cho lựa chọn mới. Dữ liệu lịch sử vẫn được giữ lại.`}
        confirmLabel="Lưu trữ bài tập"
        cancelLabel="Hủy"
        confirmDisabled={archiveReason.trim().length < 1 || archiveReason.trim().length > 1000}
        busy={actionMutation.isPending}
        destructive
        onCancel={closeAction}
        onConfirm={confirmAction}
      >
        <label className="form-field" htmlFor="archive-reason">
          Lý do lưu trữ
          <textarea
            id="archive-reason"
            value={archiveReason}
            maxLength={1000}
            aria-required="true"
            aria-invalid={Boolean(archiveReasonError)}
            aria-describedby={archiveReasonError ? 'archive-reason-error archive-reason-count' : 'archive-reason-count'}
            disabled={actionMutation.isPending}
            onChange={(event) => {
              setArchiveReason(event.target.value)
              setArchiveReasonError(undefined)
            }}
          />
        </label>
        {archiveReasonError ? <p id="archive-reason-error" className="field-error">{archiveReasonError}</p> : null}
        <p id="archive-reason-count" className="form-help">{archiveReason.length.toLocaleString('vi-VN')} / 1.000 ký tự</p>
      </ConfirmationDialog>
    </section>
  )
}

function mapLifecycleActionError(error: unknown): {
  content?: ActionErrorContent
  terminal?: TerminalActionError
} {
  if (error instanceof ApiError) {
    if (error.status === 401 || ['SESSION_EXPIRED', 'UNAUTHENTICATED', 'INVALID_REFRESH_TOKEN'].includes(error.errorCode)) {
      return { terminal: { title: 'Phiên đăng nhập đã hết hạn', description: 'Vui lòng đăng nhập lại để tiếp tục.' } }
    }
    if (error.status === 403 || ['ACCESS_DENIED', 'ACCOUNT_UNAVAILABLE', 'CATALOG_MANAGE_REQUIRED'].includes(error.errorCode)) {
      return {
        terminal: {
          title: error.errorCode === 'ACCOUNT_UNAVAILABLE' ? 'Tài khoản không khả dụng' : 'Bạn không có quyền thực hiện thao tác',
          description: 'Quyền quản lý danh mục hiện không khả dụng. Chi tiết đã được ẩn.',
        },
      }
    }
    if (error.status === 404 || error.errorCode === 'ADMIN_EXERCISE_NOT_FOUND') {
      return { terminal: { title: 'Không tìm thấy bài tập', description: 'Bài tập này không tồn tại hoặc hiện không khả dụng.' } }
    }
    if (error.errorCode === 'EXERCISE_VERSION_CONFLICT') {
      return {
        content: {
          title: 'Bài tập đã được cập nhật',
          description: 'Phiên bản đang hiển thị không còn mới nhất. Hãy tải lại và kiểm tra trước khi thực hiện lại.',
          refreshRecommended: true,
        },
      }
    }
    if (error.errorCode === 'EXERCISE_LIFECYCLE_CONFLICT') {
      return {
        content: {
          title: 'Trạng thái bài tập đã thay đổi',
          description: 'Thao tác không còn phù hợp với trạng thái hiện tại. Hãy tải lại chi tiết để kiểm tra.',
          refreshRecommended: true,
        },
      }
    }
    if (error.errorCode === 'EXERCISE_CANONICAL_CONFLICT') {
      return {
        content: {
          title: 'Chưa thể lưu trữ bài tập',
          description: 'Bài tập đang là mục thay thế chuẩn cho nội dung khác. Hãy kiểm tra quan hệ thay thế trước khi thử lại.',
        },
      }
    }
    if (error.errorCode === 'VALIDATION_FAILED') {
      return {
        content: {
          title: 'Bài tập chưa đủ điều kiện kích hoạt',
          description: 'Hãy chỉnh sửa và hoàn tất các thông tin bắt buộc trước khi kích hoạt.',
        },
      }
    }
    if (error.status === 0) {
      return { content: { title: 'Không thể kết nối đến hệ thống', description: 'Thao tác chưa được xác nhận. Hãy kiểm tra kết nối trước khi thử lại.' } }
    }
    if (error.status >= 500) {
      return { content: { title: 'Hệ thống đang gặp sự cố', description: 'Thao tác chưa được xác nhận. Vui lòng thử lại sau.' } }
    }
  }
  return { content: { title: 'Không thể hoàn tất thao tác', description: 'Thao tác chưa được xác nhận. Vui lòng kiểm tra và thử lại.' } }
}

function DetailState({
  title,
  description,
  onBack,
  onRetry,
}: {
  title: string
  description: string
  onBack: () => void
  onRetry?: () => void
}) {
  return (
    <section className="page-stack">
      <BackButton onBack={onBack} />
      <StatePanel
        title={title}
        description={description}
        tone="danger"
        action={onRetry ? <button onClick={onRetry}>Thử lại</button> : null}
      />
    </section>
  )
}

function BackButton({ onBack }: { onBack: () => void }) {
  return <button className="back-button" onClick={onBack}>← Quay lại danh sách</button>
}

function DetailField({ label, value }: { label: string; value: string | null }) {
  if (!value) return null
  return <div><dt>{label}</dt><dd>{value}</dd></div>
}

function VariationCard({ variation }: { variation: AdminExerciseVariation }) {
  return (
    <article className="variation-card">
      <header>
        <h3>{variation.name}</h3>
        <div className="variation-markers">
          {variation.defaultVariation ? <span>Mặc định</span> : null}
          <span>{variation.active ? 'Đang hoạt động' : 'Không hoạt động'}</span>
        </div>
      </header>
      <p className="muted">{variation.code}</p>
      {variation.difficulty ? <p><strong>Độ khó:</strong> {difficultyLabels[variation.difficulty]}</p> : null}
      {variation.description ? <p>{variation.description}</p> : null}
      {variation.instructions ? <p className="pre-line"><strong>Hướng dẫn:</strong> {variation.instructions}</p> : null}
      {variation.muscles.length > 0 ? (
        <p><strong>Nhóm cơ:</strong> {variation.muscles.map((muscle) => muscle.muscleGroupCode).join(', ')}</p>
      ) : null}
      {variation.equipment.length > 0 ? (
        <p><strong>Thiết bị:</strong> {variation.equipment.map((equipment) => equipment.equipmentCode).join(', ')}</p>
      ) : null}
    </article>
  )
}
