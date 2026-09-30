import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { StatePanel } from '../../components/StatePanel.tsx'
import { isRetryableApiError } from '../../services/apiClient.ts'
import { exerciseQueryKeys, getAdminExerciseDetail } from '../../services/exerciseApi.ts'
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

export function ExerciseDetailPage() {
  const { exerciseId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const isValidExerciseId = UUID_PATTERN.test(exerciseId)
  const detailQuery = useQuery({
    queryKey: exerciseQueryKeys.detail(exerciseId),
    queryFn: () => getAdminExerciseDetail(exerciseId),
    enabled: isValidExerciseId,
  })
  const state = location.state as DetailLocationState | null
  const goBack = () => navigate(state?.from?.startsWith('/exercises') ? state.from : '/exercises')

  if (!isValidExerciseId) {
    return (
      <DetailState
        title="Đường dẫn bài tập không hợp lệ"
        description="Hãy quay lại danh sách và chọn một bài tập khác."
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

  return (
    <section className="page-stack">
      <BackButton onBack={goBack} />
      {canRenderStaleDetail ? (
        <div className="inline-alert" role="status">
          Không thể cập nhật dữ liệu mới. Chi tiết gần nhất vẫn đang được hiển thị.
          <button className="button-link-inline" onClick={() => void detailQuery.refetch()}>Thử lại</button>
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
            <Link className="button-link" to={`/exercises/${exercise.id}/edit`}>Chỉnh sửa</Link>
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
    </section>
  )
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
