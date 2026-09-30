import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { ConfirmationDialog } from '../../components/ConfirmationDialog.tsx'
import { StatePanel } from '../../components/StatePanel.tsx'
import { ApiError, isRetryableApiError } from '../../services/apiClient.ts'
import {
  createAdminExerciseDraft,
  exerciseQueryKeys,
  getAdminExerciseDetail,
  getAdminExerciseFormMetadata,
  updateAdminExerciseDraft,
} from '../../services/exerciseApi.ts'
import type { AdminExerciseDraftRequest } from '../../types/exercise.ts'
import { ExerciseDraftForm } from './ExerciseDraftForm.tsx'
import {
  createEmptyExerciseDraft,
  exerciseDetailToFormValue,
  mapApiFieldErrors,
  toAdminExerciseDraftRequest,
} from './exerciseDraftForm.ts'
import type { ExerciseDraftFormErrors, ExerciseDraftFormValue } from './exerciseDraftForm.ts'
import { getExerciseErrorContent } from './exercisePresentation.ts'
import { useUnsavedChanges } from './useUnsavedChanges.tsx'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export function ExerciseCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [value, setValue] = useState(createEmptyExerciseDraft)
  const [baseline, setBaseline] = useState(() => serializeDraft(value))
  const [serverErrors, setServerErrors] = useState<ExerciseDraftFormErrors>({})
  const [formError, setFormError] = useState<string>()
  const [terminalError, setTerminalError] = useState<TerminalFormError>()
  const dirty = serializeDraft(value) !== baseline
  const { requestNavigation, unsavedChangesDialog } = useUnsavedChanges(dirty)
  const metadataQuery = useQuery({
    queryKey: exerciseQueryKeys.metadata(),
    queryFn: getAdminExerciseFormMetadata,
  })
  const mutation = useMutation({
    mutationFn: createAdminExerciseDraft,
    onSuccess: async (created) => {
      setBaseline(serializeDraft(value))
      queryClient.setQueryData(exerciseQueryKeys.detail(created.id), created)
      await queryClient.invalidateQueries({ queryKey: exerciseQueryKeys.lists() })
      navigate(`/exercises/${created.id}`, { replace: true })
    },
    onError: (error) => applyMutationError(error, setServerErrors, setFormError, setTerminalError),
  })

  if (terminalError) {
    return <TerminalFormState error={terminalError} onCatalog={() => navigate('/exercises')} />
  }

  if (metadataQuery.isPending) return <FormLoading title="Tạo bài tập" />
  if (metadataQuery.isError || !metadataQuery.data) {
    return <MetadataError error={metadataQuery.error} onRetry={() => void metadataQuery.refetch()} />
  }

  return (
    <section className="page-stack">
      <FormHeading eyebrow="Tạo bản nháp" title="Tạo bài tập" description="Bài tập mới sẽ được lưu ở trạng thái Bản nháp." />
      <EmptyMetadataNotice metadata={metadataQuery.data} />
      <ExerciseDraftForm
        mode="create"
        value={value}
        metadata={metadataQuery.data}
        busy={mutation.isPending}
        serverErrors={serverErrors}
        formError={formError}
        onChange={(next) => {
          setValue(next)
          setServerErrors({})
          setFormError(undefined)
        }}
        onCancel={() => requestNavigation('/exercises')}
        onSubmit={(request) => {
          setServerErrors({})
          setFormError(undefined)
          mutation.mutate(request)
        }}
      />
      {unsavedChangesDialog}
    </section>
  )
}

export function ExerciseEditPage() {
  const { exerciseId = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const validId = UUID_PATTERN.test(exerciseId)
  const [value, setValue] = useState<ExerciseDraftFormValue>()
  const [baseline, setBaseline] = useState('')
  const [loadedVersion, setLoadedVersion] = useState<number>()
  const [serverErrors, setServerErrors] = useState<ExerciseDraftFormErrors>({})
  const [formError, setFormError] = useState<string>()
  const [terminalError, setTerminalError] = useState<TerminalFormError>()
  const [versionConflict, setVersionConflict] = useState(false)
  const [confirmReload, setConfirmReload] = useState(false)
  const dirty = Boolean(value && baseline && serializeDraft(value) !== baseline)
  const { requestNavigation, unsavedChangesDialog } = useUnsavedChanges(dirty)
  const metadataQuery = useQuery({
    queryKey: exerciseQueryKeys.metadata(),
    queryFn: getAdminExerciseFormMetadata,
  })
  const detailQuery = useQuery({
    queryKey: exerciseQueryKeys.detail(exerciseId),
    queryFn: () => getAdminExerciseDetail(exerciseId),
    enabled: validId,
  })

  if (!value && detailQuery.data?.status === 'DRAFT') {
    const initialValue = exerciseDetailToFormValue(detailQuery.data)
    setValue(initialValue)
    setBaseline(serializeDraft(initialValue))
    setLoadedVersion(detailQuery.data.version)
  }

  const mutation = useMutation({
    mutationFn: ({ request, version }: { request: AdminExerciseDraftRequest; version: number }) =>
      updateAdminExerciseDraft(exerciseId, { expectedVersion: version, exercise: request }),
    onSuccess: async (updated) => {
      const updatedValue = exerciseDetailToFormValue(updated)
      setValue(updatedValue)
      setBaseline(serializeDraft(updatedValue))
      queryClient.setQueryData(exerciseQueryKeys.detail(updated.id), updated)
      await queryClient.invalidateQueries({ queryKey: exerciseQueryKeys.lists() })
      navigate(`/exercises/${updated.id}`, { replace: true })
    },
    onError: (error) => {
      if (error instanceof ApiError && error.errorCode === 'EXERCISE_VERSION_CONFLICT') {
        setVersionConflict(true)
        setFormError('Bài tập đã được người khác cập nhật. Dữ liệu bạn đang nhập vẫn được giữ nguyên.')
        return
      }
      applyMutationError(error, setServerErrors, setFormError, setTerminalError)
    },
  })

  if (!validId) {
    return (
      <StatePanel
        title="Đường dẫn bài tập không hợp lệ"
        description="Hãy quay lại danh sách và chọn một bài tập khác."
        tone="danger"
        action={<button onClick={() => navigate('/exercises')}>Quay lại danh sách</button>}
      />
    )
  }
  if (terminalError) {
    return <TerminalFormState error={terminalError} onCatalog={() => navigate('/exercises')} exerciseId={exerciseId} />
  }
  if (metadataQuery.isPending || detailQuery.isPending) return <FormLoading title="Chỉnh sửa bài tập" />
  if (metadataQuery.isError || !metadataQuery.data) {
    return <MetadataError error={metadataQuery.error} onRetry={() => void metadataQuery.refetch()} />
  }

  const canRenderStaleDetail = detailQuery.isError && detailQuery.data && isRetryableApiError(detailQuery.error)
  if (detailQuery.isError && !canRenderStaleDetail) {
    const content = getExerciseErrorContent(detailQuery.error, 'detail')
    return (
      <StatePanel
        title={content.title}
        description={content.description}
        tone="danger"
        action={content.retryable ? <button onClick={() => void detailQuery.refetch()}>Thử lại</button> : null}
      />
    )
  }

  const detail = detailQuery.data
  if (!detail) return null
  if (detail.status !== 'DRAFT') {
    return (
      <StatePanel
        title="Bài tập này không thể chỉnh sửa"
        description={`Chỉ bài tập ở trạng thái Bản nháp mới có thể chỉnh sửa. ${detail.name} hiện chỉ có thể xem.`}
        tone="danger"
        action={<button onClick={() => navigate(`/exercises/${detail.id}`)}>Xem chi tiết bài tập</button>}
      />
    )
  }
  if (!value || loadedVersion === undefined) return <FormLoading title="Chỉnh sửa bài tập" />

  const reloadLatest = async () => {
    const latest = await queryClient.fetchQuery({
      queryKey: exerciseQueryKeys.detail(exerciseId),
      queryFn: () => getAdminExerciseDetail(exerciseId),
      staleTime: 0,
    })
    if (latest.status !== 'DRAFT') {
      setTerminalError({
        title: 'Bài tập không còn là bản nháp',
        description: 'Bài tập đã chuyển trạng thái và không thể tiếp tục chỉnh sửa.',
      })
      return
    }
    const latestValue = exerciseDetailToFormValue(latest)
    setValue(latestValue)
    setBaseline(serializeDraft(latestValue))
    setLoadedVersion(latest.version)
    setVersionConflict(false)
    setFormError(undefined)
    setServerErrors({})
  }

  return (
    <section className="page-stack">
      <FormHeading eyebrow="Chỉnh sửa bản nháp" title={detail.name} description={`Phiên bản ${detail.version.toLocaleString('vi-VN')}`} />
      {canRenderStaleDetail ? (
        <div className="inline-alert" role="status">
          Không thể tải dữ liệu mới nhất. Bạn nên thử lại trước khi chỉnh sửa.
          <button className="button-link-inline" onClick={() => void detailQuery.refetch()}>Thử lại</button>
        </div>
      ) : null}
      {versionConflict ? (
        <div className="conflict-panel" role="alert">
          <h2>Có phiên bản mới hơn trên hệ thống</h2>
          <p>Dữ liệu bạn nhập chưa bị mất. Bạn có thể tải bản mới nhất hoặc quay lại trang chi tiết.</p>
          <div className="inline-actions">
            <button type="button" onClick={() => setConfirmReload(true)}>Tải bản mới nhất</button>
            <button type="button" className="button-secondary" onClick={() => requestNavigation(`/exercises/${exerciseId}`)}>Quay lại chi tiết</button>
          </div>
        </div>
      ) : null}
      <EmptyMetadataNotice metadata={metadataQuery.data} />
      <ExerciseDraftForm
        mode="edit"
        value={value}
        metadata={metadataQuery.data}
        busy={mutation.isPending}
        serverErrors={serverErrors}
        formError={formError}
        onChange={(next) => {
          setValue(next)
          setServerErrors({})
          if (!versionConflict) setFormError(undefined)
        }}
        onCancel={() => requestNavigation(`/exercises/${exerciseId}`)}
        onSubmit={(request) => {
          setServerErrors({})
          setFormError(undefined)
          mutation.mutate({ request, version: loadedVersion })
        }}
      />
      {unsavedChangesDialog}
      <ConfirmationDialog
        open={confirmReload}
        title="Tải bản mới nhất?"
        description="Toàn bộ thay đổi đang nhập trên trang này sẽ bị thay thế bằng dữ liệu mới nhất từ hệ thống."
        confirmLabel="Tải và bỏ thay đổi"
        onCancel={() => setConfirmReload(false)}
        onConfirm={() => {
          setConfirmReload(false)
          void reloadLatest().catch((error: unknown) => {
            applyMutationError(error, setServerErrors, setFormError, setTerminalError)
          })
        }}
      />
    </section>
  )
}

function serializeDraft(value: ExerciseDraftFormValue): string {
  return JSON.stringify(toAdminExerciseDraftRequest(value))
}

interface TerminalFormError {
  title: string
  description: string
}

function applyMutationError(
  error: unknown,
  setServerErrors: (errors: ExerciseDraftFormErrors) => void,
  setFormError: (message: string | undefined) => void,
  setTerminalError: (error: TerminalFormError | undefined) => void,
) {
  if (error instanceof ApiError) {
    if (error.errorCode === 'EXERCISE_CODE_CONFLICT') {
      setServerErrors({ code: 'Mã bài tập đã được sử dụng. Vui lòng chọn mã khác.' })
      setFormError('Mã bài tập bị trùng với dữ liệu hiện có.')
      return
    }
    if (error.errorCode === 'EXERCISE_LIFECYCLE_CONFLICT') {
      setTerminalError({
        title: 'Bài tập không còn có thể chỉnh sửa',
        description: 'Trạng thái bài tập đã thay đổi. Hãy quay lại trang chi tiết hoặc danh sách.',
      })
      return
    }
    if (error.status === 401 || ['SESSION_EXPIRED', 'UNAUTHENTICATED', 'INVALID_REFRESH_TOKEN'].includes(error.errorCode)) {
      setTerminalError({
        title: 'Phiên đăng nhập đã hết hạn',
        description: 'Vui lòng đăng nhập lại để tiếp tục.',
      })
      return
    }
    if (error.status === 403 || ['ACCESS_DENIED', 'ACCOUNT_UNAVAILABLE', 'CATALOG_MANAGE_REQUIRED'].includes(error.errorCode)) {
      setTerminalError({
        title: error.errorCode === 'ACCOUNT_UNAVAILABLE' ? 'Tài khoản không khả dụng' : 'Bạn không có quyền chỉnh sửa',
        description: 'Biểu mẫu đã được khóa vì quyền quản lý danh mục hiện không khả dụng.',
      })
      return
    }
    if (error.status === 404 || error.errorCode === 'ADMIN_EXERCISE_NOT_FOUND') {
      setTerminalError({
        title: 'Không tìm thấy bài tập',
        description: 'Bài tập này không tồn tại hoặc hiện không khả dụng.',
      })
      return
    }
    if (error.errorCode === 'VALIDATION_FAILED') {
      const fieldErrors = mapApiFieldErrors(error.fieldErrors)
      setServerErrors(fieldErrors)
      setFormError(Object.keys(fieldErrors).length > 0
        ? 'Một số trường chưa hợp lệ. Vui lòng kiểm tra các lỗi bên dưới.'
        : 'Dữ liệu chưa hợp lệ. Vui lòng kiểm tra lại biểu mẫu.')
      return
    }
    if (error.status === 0) {
      setFormError('Không thể kết nối đến hệ thống. Dữ liệu bạn nhập vẫn được giữ để thử lại.')
      return
    }
    if (error.status >= 500) {
      setFormError('Hệ thống đang gặp sự cố. Dữ liệu bạn nhập vẫn được giữ để thử lại sau.')
      return
    }
  }
  setFormError('Không thể lưu bài tập. Dữ liệu bạn nhập vẫn được giữ để thử lại.')
}

function FormHeading({ eyebrow, title, description }: { eyebrow: string; title: string; description: string }) {
  return (
    <header>
      <p className="eyebrow">{eyebrow}</p>
      <h1>{title}</h1>
      <p className="muted">{description}</p>
    </header>
  )
}

function FormLoading({ title }: { title: string }) {
  return (
    <section className="page-stack" aria-busy="true">
      <FormHeading eyebrow="Quản lý danh mục" title={title} description="Đang chuẩn bị biểu mẫu…" />
      <div className="surface skeleton-stack" aria-label="Đang tải biểu mẫu bài tập">
        <div className="skeleton-line skeleton-line--short" />
        <div className="skeleton-line" />
        <div className="skeleton-line" />
      </div>
    </section>
  )
}

function MetadataError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const content = getExerciseErrorContent(error, 'list')
  return (
    <StatePanel
      title={content.title === 'Không thể tải dữ liệu' ? 'Không thể tải dữ liệu biểu mẫu' : content.title}
      description={content.description}
      tone="danger"
      action={content.retryable ? <button onClick={onRetry}>Thử lại</button> : null}
    />
  )
}

function EmptyMetadataNotice({ metadata }: { metadata: Awaited<ReturnType<typeof getAdminExerciseFormMetadata>> }) {
  const emptyGroups = [
    ['danh mục', metadata.categories],
    ['nhóm cơ', metadata.muscleGroups],
    ['thiết bị', metadata.equipment],
    ['nhãn', metadata.tags],
    ['kiểu vận động', metadata.movementPatterns],
  ].filter(([, options]) => options.length === 0).map(([label]) => label)
  if (emptyGroups.length === 0) return null
  return (
    <div className="inline-alert" role="status">
      Chưa có lựa chọn khả dụng cho: {emptyGroups.join(', ')}. Bạn vẫn có thể lưu các trường hợp lệ khác của bản nháp.
    </div>
  )
}

function TerminalFormState({
  error,
  onCatalog,
  exerciseId,
}: {
  error: TerminalFormError
  onCatalog: () => void
  exerciseId?: string
}) {
  const navigate = useNavigate()
  return (
    <StatePanel
      title={error.title}
      description={error.description}
      tone="danger"
      action={(
        <div className="inline-actions inline-actions--center">
          {exerciseId ? <button onClick={() => navigate(`/exercises/${exerciseId}`)}>Quay lại chi tiết</button> : null}
          <button className={exerciseId ? 'button-secondary' : undefined} onClick={onCatalog}>Quay lại danh sách</button>
        </div>
      )}
    />
  )
}
