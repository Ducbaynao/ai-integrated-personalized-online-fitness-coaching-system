import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { StatePanel } from '../../components/StatePanel.tsx'
import { exerciseQueryKeys, getAdminExercises } from '../../services/exerciseApi.ts'
import type { ExerciseLifecycleStatus, ExerciseSearchParams } from '../../types/exercise.ts'
import {
  difficultyLabels,
  formatDateTime,
  getExerciseErrorContent,
  lifecycleLabels,
} from './exercisePresentation.ts'
import {
  parseExerciseSearchParams,
  serializeExerciseSearchParams,
} from './exerciseSearchState.ts'
import { useDebouncedValue } from './useDebouncedValue.ts'

const STATUS_OPTIONS: Array<{ value: ExerciseLifecycleStatus | ''; label: string }> = [
  { value: '', label: 'Tất cả trạng thái' },
  { value: 'DRAFT', label: lifecycleLabels.DRAFT },
  { value: 'ACTIVE', label: lifecycleLabels.ACTIVE },
  { value: 'ARCHIVED', label: lifecycleLabels.ARCHIVED },
]

export function ExerciseListPage() {
  const location = useLocation()
  return <ExerciseListContent key={location.search} />
}

function ExerciseListContent() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const params = useMemo(() => parseExerciseSearchParams(searchParams), [searchParams])
  const canonicalSearch = useMemo(() => serializeExerciseSearchParams(params), [params])
  const [searchValue, setSearchValue] = useState(params.query ?? '')
  const debouncedSearchValue = useDebouncedValue(searchValue, 350)

  useEffect(() => {
    if (searchParams.toString() !== canonicalSearch.toString()) {
      setSearchParams(canonicalSearch, { replace: true })
    }
  }, [canonicalSearch, searchParams, setSearchParams])

  useEffect(() => {
    const normalizedQuery = debouncedSearchValue.trim().slice(0, 120) || undefined
    if (normalizedQuery === params.query) return
    setSearchParams(
      serializeExerciseSearchParams({ ...params, query: normalizedQuery, page: 0 }),
      { replace: true },
    )
  }, [debouncedSearchValue, params, setSearchParams])

  const exercisesQuery = useQuery({
    queryKey: exerciseQueryKeys.list(params),
    queryFn: () => getAdminExercises(params),
  })

  const updateParams = (next: Partial<ExerciseSearchParams>) => {
    setSearchParams(serializeExerciseSearchParams({ ...params, ...next }))
  }

  if (exercisesQuery.isPending) {
    return (
      <section className="page-stack" aria-busy="true">
        <PageHeading />
        <ExerciseFilters
          searchValue={searchValue}
          status={params.status}
          onSearchChange={setSearchValue}
          onStatusChange={(status) => updateParams({ status, page: 0 })}
        />
        <div className="surface skeleton-stack" aria-label="Đang tải danh sách bài tập">
          <div className="skeleton-line" />
          <div className="skeleton-line" />
          <div className="skeleton-line" />
        </div>
      </section>
    )
  }

  if (exercisesQuery.isError && !exercisesQuery.data) {
    const content = getExerciseErrorContent(exercisesQuery.error, 'list')
    return (
      <section className="page-stack">
        <PageHeading />
        <ExerciseFilters
          searchValue={searchValue}
          status={params.status}
          onSearchChange={setSearchValue}
          onStatusChange={(status) => updateParams({ status, page: 0 })}
        />
        <StatePanel
          title={content.title}
          description={content.description}
          tone="danger"
          headingLevel={2}
          action={content.retryable ? <button onClick={() => void exercisesQuery.refetch()}>Thử lại</button> : null}
        />
      </section>
    )
  }

  const data = exercisesQuery.data
  if (!data) return null
  const hasCriteria = Boolean(params.query || params.status)

  return (
    <section className="page-stack">
      <PageHeading />
      <ExerciseFilters
        searchValue={searchValue}
        status={params.status}
        onSearchChange={setSearchValue}
        onStatusChange={(status) => updateParams({ status, page: 0 })}
      />

      {exercisesQuery.isError ? (
        <div className="inline-alert" role="status">
          Không thể cập nhật dữ liệu mới. Danh sách gần nhất vẫn đang được hiển thị.
          <button className="button-link-inline" onClick={() => void exercisesQuery.refetch()}>Thử lại</button>
        </div>
      ) : null}

      {data.items.length === 0 ? (
        <StatePanel
          title={hasCriteria ? 'Không có kết quả phù hợp' : 'Danh mục chưa có bài tập'}
          description={hasCriteria ? 'Hãy thử từ khóa hoặc trạng thái khác.' : 'Bài tập sẽ xuất hiện tại đây khi được tạo.'}
          headingLevel={2}
          action={hasCriteria ? <button onClick={() => setSearchParams(new URLSearchParams())}>Xóa bộ lọc</button> : null}
        />
      ) : (
        <div className="surface table-surface">
          <div className="table-summary">
            <p><strong>{data.totalItems.toLocaleString('vi-VN')}</strong> bài tập</p>
            <p className="muted">Sắp xếp theo tên A–Z</p>
          </div>
          <div className="table-scroll">
            <table>
              <caption className="sr-only">Danh sách bài tập quản trị</caption>
              <thead>
                <tr>
                  <th scope="col">Tên bài tập</th>
                  <th scope="col">Mã</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">Độ khó</th>
                  <th scope="col">Biến thể</th>
                  <th scope="col">Cập nhật</th>
                  <th scope="col">Thao tác</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((exercise) => (
                  <tr key={exercise.id}>
                    <td>
                      <Link
                        to={`/exercises/${exercise.id}`}
                        state={{ from: `${location.pathname}${location.search}` }}
                      >
                        {exercise.name}
                      </Link>
                      {exercise.categoryCode ? <span className="cell-detail">{exercise.categoryCode}</span> : null}
                    </td>
                    <td>{exercise.code}</td>
                    <td><span className={`status-badge status-badge--${exercise.status.toLowerCase()}`}>{lifecycleLabels[exercise.status]}</span></td>
                    <td>{exercise.difficulty ? difficultyLabels[exercise.difficulty] : '—'}</td>
                    <td>{exercise.variationCount.toLocaleString('vi-VN')}</td>
                    <td>{formatDateTime(exercise.updatedAt)}</td>
                    <td>
                      {exercise.status === 'DRAFT' ? (
                        <Link to={`/exercises/${exercise.id}/edit`}>Chỉnh sửa</Link>
                      ) : <span className="muted">Chỉ xem</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="pagination" aria-label="Phân trang danh sách bài tập">
            <label>
              Số dòng
              <select
                value={params.size}
                onChange={(event) => updateParams({ size: Number(event.target.value), page: 0 })}
              >
                {[10, 20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}
              </select>
            </label>
            <span>Trang {(data.page + 1).toLocaleString('vi-VN')} / {Math.max(data.totalPages, 1).toLocaleString('vi-VN')}</span>
            <button
              className="button-secondary"
              disabled={data.page <= 0 || exercisesQuery.isFetching}
              onClick={() => updateParams({ page: data.page - 1 })}
            >
              Trang trước
            </button>
            <button
              className="button-secondary"
              disabled={data.page + 1 >= data.totalPages || exercisesQuery.isFetching}
              onClick={() => updateParams({ page: data.page + 1 })}
            >
              Trang sau
            </button>
          </div>
        </div>
      )}
    </section>
  )
}

function PageHeading() {
  return (
    <div className="page-heading-row">
      <div>
        <p className="eyebrow">Quản lý danh mục</p>
        <h1>Bài tập</h1>
        <p className="muted">Theo dõi bài tập ở mọi trạng thái vòng đời.</p>
      </div>
      <Link className="button-link" to="/exercises/new">Tạo bài tập</Link>
    </div>
  )
}

interface ExerciseFiltersProps {
  searchValue: string
  status?: ExerciseLifecycleStatus
  onSearchChange: (value: string) => void
  onStatusChange: (value: ExerciseLifecycleStatus | undefined) => void
}

function ExerciseFilters({ searchValue, status, onSearchChange, onStatusChange }: ExerciseFiltersProps) {
  return (
    <div className="filter-bar" role="search" aria-label="Tìm và lọc bài tập">
      <label>
        Tìm kiếm
        <input
          type="search"
          value={searchValue}
          maxLength={120}
          placeholder="Tìm theo tên hoặc mã bài tập"
          onChange={(event) => onSearchChange(event.target.value)}
        />
      </label>
      <label>
        Trạng thái
        <select
          value={status ?? ''}
          onChange={(event) => onStatusChange(event.target.value as ExerciseLifecycleStatus || undefined)}
        >
          {STATUS_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </label>
    </div>
  )
}
